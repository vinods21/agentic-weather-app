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
import java.time.LocalDate;
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
 * AC-20/AC-21: the shared overall deadline ({@code weather.favourites.weather.timeout=1s}) with a
 * per-call read timeout of 5 s, so only the deadline can end a 3 s upstream call within the 2 s bound.
 *
 * <p>The read timeout is set through {@code @DynamicPropertySource}, because
 * {@code FakeOpenMeteo.RealProviderInitializer} writes {@code read-timeout=1s} into the inlined
 * property source and would override a class-level {@code @TestPropertySource} value. The guard asserts
 * the effective bound values before every test.
 * <p>{@code @DirtiesContext}: this class has its own context; closing it after the class releases its
 * Hikari pool, so the run stays under Postgres's {@code max_connections}.
 */
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "weather.favourites.weather.max-concurrency=4",
    "weather.favourites.weather.timeout=1s",
    "weather.favourites.max-per-client=20",
})
class FavouritesForecastDeadlineIT extends CachingIntegrationTestBase {

    static final String FORECAST = "/api/v1/favourites/forecast";
    static final Duration SLOW = Duration.ofSeconds(3);
    static final LocalDate START = LocalDate.of(2026, 11, 15);

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
    private InFlightResponder responder;

    @BeforeEach
    void guardEffectiveTimeouts() {
        assertThat(environment.getProperty("weather.provider.read-timeout")).isEqualTo("5s");
        assertThat(providerProperties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(favouritesWeatherProperties.timeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(favouritesWeatherProperties.maxConcurrency()).isEqualTo(4);
    }

    @AfterEach
    void drainAndDeleteRows() {
        // A late handler must not leak into the next test's fake.reset().
        if (responder != null) {
            responder.awaitIdle(Duration.ofSeconds(5));
        }
        clients.forEach(c -> FavouriteRows.deleteAll(jdbc, c));
        clients.clear();
    }

    private String newClient() {
        String c = "it-ffc-dl-" + UUID.randomUUID();
        clients.add(c);
        return c;
    }

    private ResponseEntity<String> forecast(String clientId, String requestId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Client-Id", clientId);
        h.set("X-Request-Id", requestId);
        return http.exchange(URI.create(FORECAST), HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    private static boolean isLatitude(RecordedRequest r, String latitude) {
        return latitude.equals(r.params().getOrDefault("latitude", List.of()).stream().findFirst().orElse(null));
    }

    /** One fast item through the whole path, not measured; then an empty cache and a clean fake. */
    private void warmUp() {
        String warm = newClient();
        FavouriteRows.insert(jdbc, warm, "Warmup", "Warmup", "XX", -12.5, -22.5, 1, T0);
        ResponseEntity<String> res = forecast(warm, "it-ffc-20-warmup");
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(json(res).at("/items/0/forecast").isMissingNode()).as(res.getBody()).isFalse();
        deleteRunRedisKeys();
        fake.reset();
    }

    @Test
    @DisplayName("AC-20 (guard): the effective provider read timeout is 5 s and the shared favourites timeout 1 s / max-concurrency 4 in this context")
    void ac20_guardEffectiveConfiguration() {
        // Assertions are in guardEffectiveTimeouts(); this test makes the guard visible on its own.
        assertThat(providerProperties.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(providerProperties.readTimeout()).isGreaterThan(SLOW);
    }

    @Test
    @DisplayName("AC-20/AC-21: Tokyo's DAILY takes 3 s against the shared 1 s deadline: 200 within 2 s (>= 900 ms), London and Paris have forecast, Tokyo UPSTREAM_UNAVAILABLE; the late result does not change the sent response")
    void ac20_deadline(CapturedOutput output) {
        warmUp();
        String client = newClient();
        List<String> ids = new ArrayList<>();
        ids.add(FavouriteRows.insert(jdbc, client, "London", "London", "GB", 51.50853, -0.12574, 1, T0).toString());
        ids.add(FavouriteRows.insert(jdbc, client, "Paris", "Paris", "FR", 48.8566, 2.3522, 2, T0).toString());
        ids.add(FavouriteRows.insert(jdbc, client, "Tokyo", "Tokyo", "JP", 35.6762, 139.6503, 3, T0).toString());
        responder = InFlightResponder.daily(r -> isLatitude(r, "35.6762") ? SLOW : Duration.ZERO);
        fake.stub(Route.DAILY, responder);

        int mark = output.getAll().length();
        long start = System.nanoTime();
        ResponseEntity<String> res = forecast(client, "it-ffc-20");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        String body = res.getBody();

        assertThat(res.getStatusCode().value()).as(body).isEqualTo(200);
        assertThat(elapsed).as("response within timeout + 1 s").isLessThan(Duration.ofSeconds(2));
        assertThat(elapsed).as("the response waited for the deadline").isGreaterThanOrEqualTo(Duration.ofMillis(900));
        JsonNode items = json(res).get("items");
        assertThat(items).extracting(i -> i.get("id").asText()).containsExactlyElementsOf(ids);
        assertThat(items.get(0).has("forecast")).as(items.get(0).toString()).isTrue();
        assertThat(items.get(1).has("forecast")).as(items.get(1).toString()).isTrue();
        assertThat(items.get(2).has("forecast")).isFalse();
        assertThat(items.get(2).at("/error/code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");

        // Let the late Tokyo handler finish, then count.
        assertThat(responder.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(received(Route.DAILY)).hasSize(3);
        assertThat(responder.started()).isEqualTo(3);
        assertThat(res.getBody()).as("the sent response is unaffected by the late result").isEqualTo(body);
        assertThat(output.getAll().substring(mark).lines()
                .filter(l -> l.contains("Favourites forecast partial failure")).toList())
                .singleElement().satisfies(l -> assertThat(l).contains("[requestId=it-ffc-20]")
                        .contains("failed=1 total=3 timedOut=1"));
    }

    @Test
    @DisplayName("AC-20: 6 items, the first 4 hang for 3 s and occupy all 4 workers: 200 within 2 s, all 6 UPSTREAM_UNAVAILABLE, and items 5 and 6 never reach the fake (no call started after the deadline)")
    void ac20_noCallStartedAfterDeadline() {
        warmUp();
        String client = newClient();
        List<String> slow = List.of("1.5", "2.5", "3.5", "4.5");
        for (int i = 0; i < 6; i++) {
            FavouriteRows.insert(jdbc, client, "City " + i, "City " + i, "XX", 1.5 + i, 7.5 + i, i + 1, T0);
        }
        responder = InFlightResponder.daily(
                r -> slow.contains(r.params().get("latitude").get(0)) ? SLOW : Duration.ZERO).gate(4);
        fake.stub(Route.DAILY, responder);

        long start = System.nanoTime();
        ResponseEntity<String> res = forecast(client, "it-ffc-20b");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(200);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        assertThat(json(res).get("items")).hasSize(6).allSatisfy(i -> {
            assertThat(i.has("forecast")).isFalse();
            assertThat(i.at("/error/code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
        });

        assertThat(responder.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(responder.gateTimedOut()).as("4 calls were in flight together").isFalse();
        assertThat(received(Route.DAILY)).extracting(r -> r.param("latitude"))
                .containsExactlyInAnyOrderElementsOf(slow);
    }
}
