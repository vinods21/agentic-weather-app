package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.provider.WeatherProviderProperties;
import com.example.weather.support.CachingIntegrationTestBase;
import com.example.weather.support.FakeOpenMeteo.RecordedRequest;
import com.example.weather.support.FakeOpenMeteo.Route;
import com.example.weather.support.FavouriteRows;
import com.example.weather.support.InFlightResponder;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * AC-17: overall deadline of 1 s with a per-call read timeout of 5 s, so only the deadline can end
 * a 3 s upstream call within the 2 s bound.
 *
 * <p>The read timeout is set through {@code @DynamicPropertySource}, because
 * {@code FakeOpenMeteo.RealProviderInitializer} writes {@code read-timeout=1s} into the inlined
 * property source and would override a class-level {@code @TestPropertySource} value. The guard
 * test asserts the effective bound value.
 * <p>{@code @DirtiesContext}: this class has its own context (no other class shares its configuration);
 * closing it after the class releases its Hikari pool, so the run stays under Postgres's
 * {@code max_connections} (each cached context keeps its pool open).
 */
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "weather.favourites.weather.max-concurrency=4",
    "weather.favourites.weather.timeout=1s",
    "weather.favourites.max-per-client=20",
})
class FavouritesWeatherDeadlineIT extends CachingIntegrationTestBase {

    static final String WEATHER = "/api/v1/favourites/weather";
    static final Duration SLOW = Duration.ofSeconds(3);

    @DynamicPropertySource
    static void readTimeout(DynamicPropertyRegistry registry) {
        registry.add("weather.provider.read-timeout", () -> "5s");
    }

    @Autowired
    Environment environment;

    @Autowired
    WeatherProviderProperties providerProperties;

    @Autowired
    FavouritesWeatherProperties favouritesWeatherProperties;

    private final Set<String> clients = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void guardEffectiveTimeouts() {
        assertThat(environment.getProperty("weather.provider.read-timeout")).isEqualTo("5s");
        assertThat(providerProperties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(favouritesWeatherProperties.timeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(favouritesWeatherProperties.maxConcurrency()).isEqualTo(4);
    }

    @AfterEach
    void deleteRows() {
        clients.forEach(c -> FavouriteRows.deleteAll(jdbc, c));
        clients.clear();
    }

    private String newClient() {
        String c = "it-faw-dl-" + UUID.randomUUID();
        clients.add(c);
        return c;
    }

    private ResponseEntity<String> weather(String clientId, String requestId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Client-Id", clientId);
        h.set("X-Request-Id", requestId);
        return http.exchange(URI.create(WEATHER), HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    private static boolean isLatitude(RecordedRequest r, String latitude) {
        return latitude.equals(r.params().getOrDefault("latitude", List.of()).stream().findFirst().orElse(null));
    }

    /** One fast item through the whole path, not measured; then an empty cache and a clean fake. */
    private void warmUp() {
        String warm = newClient();
        FavouriteRows.insert(jdbc, warm, "Warmup", "Warmup", "XX", -11.5, -21.5, 1, T0);
        assertThat(weather(warm, "it-faw-17-warmup").getStatusCode().value()).isEqualTo(200);
        deleteRunRedisKeys();
        fake.reset();
    }

    @Test
    @DisplayName("AC-17 (guard): the effective provider read timeout is 5 s and the favourites timeout 1 s in this context")
    void ac17_guardEffectiveConfiguration() {
        // Assertions are in guardEffectiveTimeouts(); this test makes the guard visible on its own.
        assertThat(providerProperties.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("AC-17: Tokyo's CURRENT takes 3 s against a 1 s deadline: 200 within 2 s, London and Paris have weather, Tokyo UPSTREAM_UNAVAILABLE; no call started after the deadline")
    void ac17_deadline(CapturedOutput output) throws InterruptedException {
        warmUp();
        String client = newClient();
        List<String> ids = new ArrayList<>();
        ids.add(FavouriteRows.insert(jdbc, client, "London", "London", "GB", 51.50853, -0.12574, 1, T0).toString());
        ids.add(FavouriteRows.insert(jdbc, client, "Paris", "Paris", "FR", 48.8566, 2.3522, 2, T0).toString());
        ids.add(FavouriteRows.insert(jdbc, client, "Tokyo", "Tokyo", "JP", 35.6762, 139.6503, 3, T0).toString());
        InFlightResponder responder = new InFlightResponder(r -> isLatitude(r, "35.6762") ? SLOW : Duration.ZERO);
        fake.stub(Route.CURRENT, responder);

        int mark = output.getAll().length();
        long start = System.nanoTime();
        ResponseEntity<String> res = weather(client, "it-faw-17");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        String body = res.getBody();

        assertThat(res.getStatusCode().value()).as(body).isEqualTo(200);
        assertThat(elapsed).as("response within timeout + 1 s").isLessThan(Duration.ofSeconds(2));
        assertThat(elapsed).as("the response waited for the deadline").isGreaterThanOrEqualTo(Duration.ofMillis(900));
        JsonNode items = json(res).get("items");
        assertThat(items).extracting(i -> i.get("id").asText()).containsExactlyElementsOf(ids);
        assertThat(items.get(0).has("weather")).isTrue();
        assertThat(items.get(1).has("weather")).isTrue();
        assertThat(items.get(2).has("weather")).isFalse();
        assertThat(items.get(2).at("/error/code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");

        // Let the late Tokyo handler finish so nothing leaks into the next test, then count.
        assertThat(responder.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(received(Route.CURRENT)).hasSize(3);
        assertThat(responder.started()).isEqualTo(3);
        assertThat(res.getBody()).as("the sent response is unaffected by the late result").isEqualTo(body);
        assertThat(output.getAll().substring(mark).lines()
                .filter(l -> l.contains("Favourites weather partial failure")).toList())
                .singleElement().satisfies(l -> assertThat(l).contains("[requestId=it-faw-17]")
                        .contains("failed=1 total=3 timedOut=1"));
    }

    @Test
    @DisplayName("AC-17: 6 items, the first 4 hang for 3 s and occupy all 4 workers: 200 within 2 s, all 6 UPSTREAM_UNAVAILABLE, and items 5 and 6 are never requested upstream")
    void ac17_noCallStartedAfterDeadline() {
        warmUp();
        String client = newClient();
        List<String> slow = List.of("1.5", "2.5", "3.5", "4.5");
        for (int i = 0; i < 6; i++) {
            FavouriteRows.insert(jdbc, client, "City " + i, "City " + i, "XX", 1.5 + i, 7.5 + i, i + 1, T0);
        }
        InFlightResponder responder = new InFlightResponder(
                r -> slow.contains(r.params().get("latitude").get(0)) ? SLOW : Duration.ZERO).gate(4);
        fake.stub(Route.CURRENT, responder);

        long start = System.nanoTime();
        ResponseEntity<String> res = weather(client, "it-faw-17b");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(200);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        assertThat(json(res).get("items")).hasSize(6).allSatisfy(i -> {
            assertThat(i.has("weather")).isFalse();
            assertThat(i.at("/error/code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
        });

        assertThat(responder.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(responder.gateTimedOut()).as("4 calls were in flight together").isFalse();
        assertThat(received(Route.CURRENT)).extracting(r -> r.param("latitude"))
                .containsExactlyInAnyOrderElementsOf(slow);
    }
}
