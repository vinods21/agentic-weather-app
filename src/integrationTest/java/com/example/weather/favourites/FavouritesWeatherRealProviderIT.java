package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.CachingIntegrationTestBase;
import com.example.weather.support.FakeOpenMeteo;
import com.example.weather.support.FakeOpenMeteo.RecordedRequest;
import com.example.weather.support.FakeOpenMeteo.Response;
import com.example.weather.support.FakeOpenMeteo.Route;
import com.example.weather.support.FavouriteRows;
import com.example.weather.support.InFlightResponder;
import com.example.weather.weather.Location;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * GET /api/v1/favourites/weather with the real Open-Meteo adapter against FakeOpenMeteo, cache on
 * and empty at the start of every test: AC-2 (real), AC-7, AC-8, AC-9, AC-11 (real), AC-12 (real),
 * AC-13, AC-16. Per-item upstream behaviour is keyed on the request's {@code latitude}, never on
 * scheduling order.
 * <p>{@code @DirtiesContext}: this class has its own context (no other class shares its configuration);
 * closing it after the class releases its Hikari pool, so the run stays under Postgres's
 * {@code max_connections} (each cached context keeps its pool open).
 */
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "weather.favourites.weather.max-concurrency=4",
    "weather.favourites.weather.timeout=5s",
    "weather.favourites.max-per-client=20",
})
class FavouritesWeatherRealProviderIT extends CachingIntegrationTestBase {

    static final String WEATHER = "/api/v1/favourites/weather";
    static final Location PARIS = new Location("Paris", "FR", 48.8566, 2.3522);
    static final Location TOKYO = new Location("Tokyo", "JP", 35.6762, 139.6503);
    static final String UPSTREAM_DETAIL = "The weather provider is temporarily unavailable.";

    private final Set<String> clients = ConcurrentHashMap.newKeySet();

    @AfterEach
    void deleteRows() {
        clients.forEach(c -> FavouriteRows.deleteAll(jdbc, c));
        clients.clear();
    }

    // ---------------------------------------------------------------- helpers

    private String newClient() {
        String c = "it-faw-real-" + UUID.randomUUID();
        clients.add(c);
        return c;
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String clientId, String body,
                                        Map<String, String> extra) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Client-Id", clientId);
        if (body != null) {
            h.set(HttpHeaders.CONTENT_TYPE, "application/json");
        }
        extra.forEach(h::set);
        return http.exchange(URI.create(path), method, new HttpEntity<>(body, h), String.class);
    }

    private ResponseEntity<String> weather(String clientId, Map<String, String> extra) {
        return send(HttpMethod.GET, WEATHER, clientId, null, extra);
    }

    private JsonNode items(ResponseEntity<String> res) {
        assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(200);
        return json(res).get("items");
    }

    /** London through the API (1 GEOCODING against the fake), Paris and Tokyo inserted directly; returns ids in order. */
    private List<String> seedLondonParisTokyo(String client, String londonLabel) {
        String body = londonLabel == null ? "{\"city\": \"London\"}"
                : "{\"city\": \"London\", \"label\": \"" + londonLabel + "\"}";
        ResponseEntity<String> created = send(HttpMethod.POST, "/api/v1/favourites", client, body, Map.of());
        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        List<String> ids = new ArrayList<>();
        ids.add(json(created).get("id").asText());
        ids.add(FavouriteRows.insert(jdbc, client, "Paris", PARIS.name(), PARIS.country(), PARIS.latitude(),
                PARIS.longitude(), 2, T0).toString());
        ids.add(FavouriteRows.insert(jdbc, client, "Tokyo", TOKYO.name(), TOKYO.country(), TOKYO.latitude(),
                TOKYO.longitude(), 3, T0).toString());
        return ids;
    }

    private static List<String> ids(JsonNode items) {
        List<String> out = new ArrayList<>();
        items.forEach(i -> out.add(i.get("id").asText()));
        return out;
    }

    private static Set<String> coordinates(List<RecordedRequest> requests) {
        Set<String> out = new HashSet<>();
        requests.forEach(r -> out.add(r.param("latitude") + "/" + r.param("longitude")));
        return out;
    }

    private static boolean isLatitude(RecordedRequest r, String latitude) {
        return latitude.equals(r.params().getOrDefault("latitude", List.of()).stream().findFirst().orElse(null));
    }

    // ---------------------------------------------------------------- AC-7, AC-8, AC-9

    @Test
    @DisplayName("AC-7/AC-8/AC-9: cold cache: 3 weather items after exactly 3 CURRENT (stored coordinates), 0 GEOCODING, 0 DAILY; one current key per location with TTL in (0, 10m]; warm repeat and /weather/current make 0 calls")
    void ac7_ac8_ac9_storedCoordinatesAndCache() {
        String client = newClient();
        List<String> ids = seedLondonParisTokyo(client, "LBL-SECRET9");
        Set<String> keysBefore = runKeys();
        fake.reset();

        ResponseEntity<String> first = weather(client, Map.of());

        // AC-7
        JsonNode items = items(first);
        assertThat(ids(items)).containsExactlyElementsOf(ids);
        items.forEach(i -> {
            assertThat(i.has("weather")).as(i.toString()).isTrue();
            assertThat(i.has("error")).isFalse();
            assertThat(i.at("/weather/location")).isEqualTo(i.get("location"));
            assertThat(i.at("/weather/observedAt").asText()).isEqualTo("2026-11-15T10:00:00Z");
        });
        assertThat(received(Route.CURRENT)).hasSize(3);
        assertThat(received(Route.GEOCODING)).isEmpty();
        assertThat(received(Route.DAILY)).isEmpty();
        assertThat(coordinates(received(Route.CURRENT)))
                .containsExactlyInAnyOrder("51.50853/-0.12574", "48.8566/2.3522", "35.6762/139.6503");

        // AC-9
        Set<String> added = new HashSet<>(runKeys());
        added.removeAll(keysBefore);
        assertThat(added).containsExactlyInAnyOrder(cacheKeys.current(LONDON), cacheKeys.current(PARIS),
                cacheKeys.current(TOKYO));
        for (String key : added) {
            assertThat(pttl(key)).as(key).isGreaterThan(0).isLessThanOrEqualTo(Duration.ofMinutes(10).toMillis());
            assertThat(key.toLowerCase()).doesNotContain(client.toLowerCase()).doesNotContain("lbl-secret9")
                    .doesNotContain("london").doesNotContain("paris").doesNotContain("tokyo");
        }
        assertThat(runKeys()).allSatisfy(k -> assertThat(k).matches(
                java.util.regex.Pattern.quote(cacheKeys.keyPrefix()) + ":(current|geocoding):v1:.*"));

        // AC-8: warm, within the TTL
        fake.reset();
        CLOCK.set(T0.plus(Duration.ofMinutes(5)));
        JsonNode warm = items(weather(client, Map.of()));
        assertThat(fake.requests()).as("no upstream request of any kind").isEmpty();
        for (int i = 0; i < 3; i++) {
            assertThat(warm.get(i).at("/weather/observedAt")).isEqualTo(items.get(i).at("/weather/observedAt"));
        }

        ResponseEntity<String> current = get("/api/v1/weather/current?city=London");
        assertThat(current.getStatusCode().value()).isEqualTo(200);
        assertThat(received(Route.CURRENT)).as("/weather/current reuses the favourite's current entry").isEmpty();
        assertThat(json(current).get("observedAt").asText()).isEqualTo("2026-11-15T10:00:00Z");
    }

    @Test
    @DisplayName("AC-8 (reverse): a current entry written by /weather/current?city=London is served to the favourites endpoint without a CURRENT request")
    void ac8_reverseDirection() {
        assertThat(get("/api/v1/weather/current?city=London").getStatusCode().value()).isEqualTo(200);
        assertThat(received(Route.CURRENT)).hasSize(1);
        String client = newClient();
        ResponseEntity<String> created = send(HttpMethod.POST, "/api/v1/favourites", client,
                "{\"city\": \"London\"}", Map.of());
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        fake.reset();
        CLOCK.set(T0.plus(Duration.ofMinutes(3)));

        JsonNode items = items(weather(client, Map.of()));

        assertThat(fake.requests()).isEmpty();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).at("/weather/observedAt").asText()).isEqualTo("2026-11-15T10:00:00Z");
    }

    // ---------------------------------------------------------------- AC-11, AC-13

    @Test
    @DisplayName("AC-11/AC-13 (real path): CURRENT 500 for Paris's latitude: 200, Paris UPSTREAM_UNAVAILABLE; endpoint and adapter WARNs carry it-faw-11; the next request (fake default) makes exactly 1 CURRENT call, for Paris")
    void ac11_ac13_realPartialFailure(CapturedOutput output) throws InterruptedException {
        String requestId = "it-faw-11";
        String client = newClient();
        List<String> ids = seedLondonParisTokyo(client, "Home");
        fake.reset();
        fake.stub(Route.CURRENT, r -> isLatitude(r, "48.8566")
                ? Response.status(500, "{\"error\":true}") : Response.ok(FakeOpenMeteo.CURRENT_AC6));

        int mark = output.getAll().length();
        JsonNode items = items(weather(client, Map.of("X-Request-Id", requestId)));
        List<String> lines = output.getAll().substring(mark).lines().toList();

        assertThat(ids(items)).containsExactlyElementsOf(ids);
        assertThat(items.get(0).has("weather")).isTrue();
        assertThat(items.get(0).get("label").asText()).isEqualTo("Home");
        assertThat(items.get(1).has("weather")).isFalse();
        assertThat(items.get(1).get("error")).isEqualTo(
                JSON.createObjectNode().put("code", "UPSTREAM_UNAVAILABLE").put("detail", UPSTREAM_DETAIL));
        assertThat(items.get(1).get("location").get("latitude").asDouble()).isEqualTo(PARIS.latitude());
        assertThat(items.get(2).has("weather")).isTrue();
        assertThat(received(Route.CURRENT)).hasSize(3);

        List<String> endpointWarns = lines.stream().filter(l -> l.contains("Favourites weather partial failure")).toList();
        assertThat(endpointWarns).singleElement().satisfies(l -> assertThat(l)
                .contains("WARN").contains("[requestId=" + requestId + "]")
                .contains("failed=1 total=3 timedOut=0")
                .doesNotContain("Paris").doesNotContain("48.8566").doesNotContain(client));
        List<String> adapterWarns = lines.stream().filter(l -> l.contains("Upstream call failed")).toList();
        assertThat(adapterWarns).singleElement().satisfies(l -> assertThat(l)
                .contains("[requestId=" + requestId + "]").contains("outcome=status=500"));

        // AC-13: failures are not cached; London and Tokyo come from the cache
        assertThat(runKeys()).doesNotContain(cacheKeys.current(PARIS))
                .contains(cacheKeys.current(LONDON), cacheKeys.current(TOKYO));
        fake.reset();
        JsonNode again = items(weather(client, Map.of()));

        assertThat(again).allSatisfy(i -> assertThat(i.has("weather")).as(i.toString()).isTrue());
        assertThat(received(Route.CURRENT)).singleElement()
                .satisfies(r -> assertThat(r.param("latitude")).isEqualTo("48.8566"));
    }

    @Test
    @DisplayName("AC-12 (real path): every CURRENT answers 500: 200 with 3 UPSTREAM_UNAVAILABLE items; after fake.reset() the same request gives 3 weather items")
    void ac12_realAllFail() {
        String client = newClient();
        seedLondonParisTokyo(client, null);
        fake.reset();
        fake.stub(Route.CURRENT, Response.status(500, "{\"error\":true}"));

        JsonNode failed = items(weather(client, Map.of()));

        assertThat(failed).hasSize(3).allSatisfy(i -> {
            assertThat(i.has("weather")).isFalse();
            assertThat(i.at("/error/code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
        });
        assertThat(runKeys()).noneMatch(k -> k.contains(":current:"));

        fake.reset();
        JsonNode ok = items(weather(client, Map.of()));
        assertThat(ok).hasSize(3).allSatisfy(i -> assertThat(i.has("weather")).isTrue());
        assertThat(received(Route.CURRENT)).hasSize(3);
    }

    // ---------------------------------------------------------------- AC-2 (real)

    @Test
    @DisplayName("AC-2 (real path): CURRENT responses complete in reverse order (P, then L, then T); the items are still in stored order T, L, P")
    void ac2_realReverseCompletion() {
        String client = newClient();
        List<String> seeded = seedLondonParisTokyo(client, null);
        String l = seeded.get(0);
        String p = seeded.get(1);
        String t = seeded.get(2);
        ResponseEntity<String> reordered = send(HttpMethod.PUT, "/api/v1/favourites/order", client,
                "{\"ids\": [\"" + t + "\", \"" + l + "\", \"" + p + "\"]}", Map.of());
        assertThat(reordered.getStatusCode().value()).isEqualTo(200);
        fake.reset();
        // T waits for L's completion, L for P's; then 200 ms each so the responses also reach the app in that order.
        Duration step = Duration.ofMillis(200);
        Duration cap = Duration.ofSeconds(5);
        InFlightResponder responder = new InFlightResponder(r -> isLatitude(r, "48.8566") ? Duration.ZERO : step)
                .gate(3)
                .before((self, r) -> {
                    if (isLatitude(r, "35.6762")) {
                        self.awaitCompleted("51.50853", cap);
                    } else if (isLatitude(r, "51.50853")) {
                        self.awaitCompleted("48.8566", cap);
                    }
                });
        fake.stub(Route.CURRENT, responder);

        JsonNode items = items(weather(client, Map.of()));

        assertThat(responder.awaitIdle(cap)).isTrue();
        assertThat(responder.gateTimedOut()).as("all three calls were in flight together").isFalse();
        assertThat(responder.completedLatitudes()).containsExactly("48.8566", "51.50853", "35.6762");
        assertThat(ids(items)).containsExactly(t, l, p);
        assertThat(items).allSatisfy(i -> assertThat(i.has("weather")).isTrue());
    }

    // ---------------------------------------------------------------- AC-16

    @Test
    @DisplayName("AC-16: max-concurrency=4, 20 cold favourites, 200 ms per CURRENT: 20 weather items in stored order, exactly 20 CURRENT requests, max in flight in [2, 4], under 4 s")
    void ac16_boundedParallelism() {
        // Warm-up (not measured): one item through the whole path, so lazy initialisation is excluded.
        String warm = newClient();
        FavouriteRows.insert(jdbc, warm, "Warmup", "Warmup", "XX", -10.5, -20.5, 1, T0);
        items(weather(warm, Map.of()));
        deleteRunRedisKeys();
        fake.reset();

        String client = newClient();
        List<String> ids = new ArrayList<>();
        List<Double> latitudes = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            double lat = 10.0 + i * 0.5;
            latitudes.add(lat);
            ids.add(FavouriteRows.insert(jdbc, client, "City " + i, "City " + i, "XX", lat, 20.0 + i * 0.25,
                    i + 1, T0).toString());
        }
        InFlightResponder responder = InFlightResponder.fixedDelay(Duration.ofMillis(200)).gate(2);
        fake.stub(Route.CURRENT, responder);

        long start = System.nanoTime();
        ResponseEntity<String> res = weather(client, Map.of());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(responder.awaitIdle(Duration.ofSeconds(5))).isTrue();
        JsonNode items = items(res);
        assertThat(ids(items)).containsExactlyElementsOf(ids);
        for (int i = 0; i < 20; i++) {
            assertThat(items.get(i).has("weather")).as(items.get(i).toString()).isTrue();
            assertThat(items.get(i).at("/weather/location/latitude").asDouble()).isEqualTo(latitudes.get(i));
        }
        assertThat(received(Route.CURRENT)).hasSize(20);
        assertThat(responder.started()).isEqualTo(20);
        assertThat(responder.gateTimedOut()).as("two calls were in flight at once").isFalse();
        assertThat(responder.maxInFlight()).isBetween(2, 4);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(4));
    }
}
