package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.cache.CacheStore;
import com.example.weather.support.CachingIntegrationTestBase;
import com.example.weather.support.FakeOpenMeteo.Route;
import com.example.weather.support.FavouriteRows;
import com.example.weather.support.IsolatedCacheStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * AC-10: the cache's {@link CacheStore} points at a closed loopback port (ADR 0007 §11, as in
 * {@code RedisUnreachableIT}); the context's own template stays on the compose Redis for cleanup.
 * No container is stopped.
 * <p>{@code @DirtiesContext}: this class has its own context (no other class shares its configuration);
 * closing it after the class releases its Hikari pool, so the run stays under Postgres's
 * {@code max_connections} (each cached context keeps its pool open).
 */
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
@Import(FavouritesWeatherRedisDownIT.ClosedPortCacheStoreConfig.class)
@TestPropertySource(properties = {
    "weather.favourites.weather.max-concurrency=4",
    "weather.favourites.weather.timeout=5s",
})
class FavouritesWeatherRedisDownIT extends CachingIntegrationTestBase {

    static final int CLOSED_PORT = closedPort();
    static final String REQUEST_ID = "it-faw-10";
    private static final Pattern WARN_OR_ERROR = Pattern.compile("\\b(WARN|ERROR)\\b");

    static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClosedPortCacheStoreConfig {
        @Bean
        @Primary
        IsolatedCacheStore closedPortCacheStore(RedisProperties redisProperties) {
            return new IsolatedCacheStore(CLOSED_PORT, redisProperties);
        }
    }

    @Autowired
    CacheStore cacheStore;

    private final String client = "it-faw-redis-" + UUID.randomUUID();

    @AfterEach
    void deleteRows() {
        FavouriteRows.deleteAll(jdbc, client);
    }

    @Test
    @DisplayName("AC-10: with the cache's Redis unreachable, 3 favourites give 200 with 3 weather items fetched upstream (3 CURRENT calls), no error items; only 'Cache operation failed' WARNs are logged")
    void ac10_failOpen(CapturedOutput output) throws InterruptedException {
        assertThat(cacheStore).isInstanceOf(IsolatedCacheStore.class);
        FavouriteRows.insert(jdbc, client, "London", "London", "GB", 51.50853, -0.12574, 1, T0);
        FavouriteRows.insert(jdbc, client, "Paris", "Paris", "FR", 48.8566, 2.3522, 2, T0);
        FavouriteRows.insert(jdbc, client, "Tokyo", "Tokyo", "JP", 35.6762, 139.6503, 3, T0);
        fake.reset();

        HttpHeaders h = new HttpHeaders();
        h.set("X-Client-Id", client);
        h.set("X-Request-Id", REQUEST_ID);
        int mark = output.getAll().length();
        ResponseEntity<String> res = http.exchange(URI.create("/api/v1/favourites/weather"), HttpMethod.GET,
                new HttpEntity<>(h), String.class);
        // The access line (and any trailing WARN) is written after the body: wait for it, then a little longer.
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && !output.getAll().substring(mark)
                .contains("GET /api/v1/favourites/weather -> 200")) {
            Thread.sleep(20);
        }
        Thread.sleep(100);

        assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(200);
        JsonNode items = json(res).get("items");
        assertThat(items).hasSize(3).allSatisfy(i -> {
            assertThat(i.has("weather")).as(i.toString()).isTrue();
            assertThat(i.has("error")).isFalse();
        });
        assertThat(received(Route.CURRENT)).hasSize(3);
        assertThat(received(Route.GEOCODING)).isEmpty();

        List<String> warnOrError = output.getAll().substring(mark).lines()
                .filter(l -> WARN_OR_ERROR.matcher(l).find()).toList();
        assertThat(warnOrError).isNotEmpty()
                .allSatisfy(l -> assertThat(l).contains("Cache operation failed").contains("[requestId=" + REQUEST_ID + "]"));
        assertThat(warnOrError).noneMatch(l -> l.contains("Favourites weather partial failure"));
    }
}
