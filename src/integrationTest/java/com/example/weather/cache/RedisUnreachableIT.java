package com.example.weather.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.CachingIntegrationTestBase;
import com.example.weather.support.FakeOpenMeteo.Response;
import com.example.weather.support.FakeOpenMeteo.Route;
import com.example.weather.support.IsolatedCacheStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The cache's own Redis connection points at a loopback port where nothing listens (AC-18..20).
 * Only the cache's {@link CacheStore} is redirected (a test {@code @Primary} bean); the context's
 * {@code StringRedisTemplate} stays on the compose Redis, so key cleanup keeps working. No
 * container is stopped.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(RedisUnreachableIT.UnreachableCacheStoreConfig.class)
class RedisUnreachableIT extends CachingIntegrationTestBase {

    static final int CLOSED_PORT = closedPort();
    static final String REQUEST_ID = "it-rwc-19";
    private static final Duration PER_REQUEST_LIMIT = Duration.ofSeconds(2);
    private static final Pattern WARN_LINE = Pattern.compile(
            "\\bWARN\\b.*\\[requestId=" + REQUEST_ID + "].*Cache operation failed op=(get|put) "
                    + "cache=(geocoding|current|forecast) reason=unavailable exception=[\\w.$]+ cause=[\\w.$]+\\s*$");
    private static final Pattern INFO_OR_ABOVE = Pattern.compile("\\b(INFO|WARN|ERROR)\\b");

    /** A port that was free a moment ago: bind, read the port, close. */
    static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class UnreachableCacheStoreConfig {
        @Bean
        @Primary
        IsolatedCacheStore unreachableCacheStore(RedisProperties redisProperties) {
            return new IsolatedCacheStore(CLOSED_PORT, redisProperties);
        }
    }

    @Autowired
    WeatherCache weatherCache;

    @Autowired
    CacheStore cacheStore;

    @Test
    @DisplayName("AC-18: with the cache's Redis unreachable the context starts and readiness is 200 UP")
    void ac18_startupAndReadinessIndependentOfCacheRedis() {
        assertThat(weatherCache).isInstanceOf(RedisWeatherCache.class);
        // Sanity: the cache's store is the redirected one, not Boot's template on the compose Redis.
        assertThat(cacheStore).isInstanceOf(IsolatedCacheStore.class);

        ResponseEntity<String> res = get("/actuator/health/readiness");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(res).get("status").asText()).isEqualTo("UP");
    }

    private record Step(String label, ResponseEntity<String> response, Duration elapsed, List<String> lines) {}

    @Test
    @DisplayName("AC-19/AC-20: Redis unreachable: 200, 200, 404, 503 as without a cache, full upstream calls, < 2 s each, 1..4 sanitised WARNs per request")
    void ac19_ac20_failOpenWhenRedisUnreachable(CapturedOutput output) {
        List<Step> steps = new ArrayList<>();

        steps.add(step("current London", "/api/v1/weather/current?city=London", output));
        assertThat(received(Route.GEOCODING)).hasSize(1);
        assertThat(received(Route.CURRENT)).hasSize(1);

        steps.add(step("forecast London", "/api/v1/weather/forecast?city=London&days=3", output));
        assertThat(received(Route.GEOCODING)).hasSize(2);
        assertThat(received(Route.DAILY)).hasSize(1);

        steps.add(step("current Atlantis", "/api/v1/weather/current?city=Atlantis", output));
        assertThat(received(Route.GEOCODING)).hasSize(3);

        fake.stub(Route.GEOCODING, Response.status(500, "{\"error\":true}"));
        steps.add(step("current London, geocoding 500", "/api/v1/weather/current?city=London", output));
        assertThat(received(Route.GEOCODING)).hasSize(4);
        assertThat(received(Route.CURRENT)).hasSize(1);

        // AC-19: statuses and bodies are the uncached ones; nothing is a 500.
        JsonNode current = json(steps.get(0).response());
        assertThat(steps.get(0).response().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(current.at("/location/name").asText()).isEqualTo("London");
        assertThat(current.get("observedAt").asText()).isEqualTo("2026-11-15T10:00:00Z");
        assertThat(current.get("temperature").asDouble()).isEqualTo(11.2);
        assertThat(current.get("windSpeed").asDouble()).isEqualTo(4.1);
        assertThat(current.get("humidity").asInt()).isEqualTo(81);

        JsonNode forecast = json(steps.get(1).response());
        assertThat(steps.get(1).response().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(forecast.get("days")).hasSize(3);
        assertThat(forecast.at("/days/0/date").asText()).isEqualTo("2026-11-15");
        assertThat(forecast.at("/days/0/minTemperature").asDouble()).isEqualTo(6.0);

        assertThat(steps.get(2).response().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(steps.get(2).response()).get("code").asText()).isEqualTo("LOCATION_NOT_FOUND");
        assertThat(json(steps.get(2).response()).get("detail").asText()).contains("Atlantis");

        assertThat(steps.get(3).response().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(json(steps.get(3).response()).get("code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(json(steps.get(3).response()).get("detail").asText())
                .isEqualTo("The weather provider is temporarily unavailable.");

        for (Step s : steps) {
            assertThat(s.response().getStatusCode().value()).as(s.label()).isNotEqualTo(500);
            assertThat(s.response().getHeaders().getFirst("X-Request-Id")).as(s.label()).isEqualTo(REQUEST_ID);
            assertThat(s.elapsed()).as(s.label() + " elapsed").isLessThan(PER_REQUEST_LIMIT);

            // AC-20: 1..4 well-formed WARN lines per request.
            List<String> cacheWarns = s.lines().stream().filter(l -> l.contains("Cache operation failed")).toList();
            assertThat(cacheWarns).as(s.label()).hasSizeBetween(1, 4)
                    .allSatisfy(l -> assertThat(l).containsPattern(WARN_LINE));

            // No INFO+ line written by the cache contains the city, a key or a value.
            s.lines().stream()
                    .filter(l -> INFO_OR_ABOVE.matcher(l).find())
                    .filter(l -> l.contains("Cache operation") || l.contains("RedisWeatherCache")
                            || l.contains(".cache."))
                    .forEach(l -> assertThat(l).as(s.label())
                            .doesNotContain("London").doesNotContain("Atlantis")
                            .doesNotContain(cacheKeys.keyPrefix() + ":")
                            .doesNotContain("11.2").doesNotContain("51.50853"));

            // No stack trace for these failures.
            assertThat(s.lines()).as(s.label() + " stack trace")
                    .noneMatch(l -> l.startsWith("\tat ") || l.matches("^\\s+at [\\w.$]+\\(.*\\)$")
                            || l.startsWith("Caused by:"));
        }

        assertThat(runKeys()).isEmpty();
    }

    private Step step(String label, String path, CapturedOutput output) {
        int mark = output.getAll().length();
        long start = System.nanoTime();
        ResponseEntity<String> res = get(path, Map.of("X-Request-Id", REQUEST_ID));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        List<String> lines = output.getAll().substring(mark).lines().toList();
        return new Step(label, res, elapsed, lines);
    }
}
