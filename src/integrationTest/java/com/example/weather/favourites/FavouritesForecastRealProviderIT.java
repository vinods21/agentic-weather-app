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
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
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
 * GET /api/v1/favourites/forecast with the real Open-Meteo adapter against FakeOpenMeteo, cache on
 * and empty at the start of every test: AC-2 (real), AC-9, AC-10, AC-11, AC-14 (real), AC-15 (real),
 * AC-16, AC-19 and the AC-27 quota regression. Per-item upstream behaviour is keyed on the request's
 * {@code latitude}, never on scheduling order.
 * <p>{@code @DirtiesContext}: this class has its own context (no other class shares its
 * configuration); closing it after the class releases its Hikari pool, so the run stays under
 * Postgres's {@code max_connections}.
 */
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "weather.favourites.weather.max-concurrency=4",
    "weather.favourites.weather.timeout=5s",
    "weather.favourites.max-per-client=20",
})
class FavouritesForecastRealProviderIT extends CachingIntegrationTestBase {

    static final String FORECAST = "/api/v1/favourites/forecast";
    static final Location PARIS = new Location("Paris", "FR", 48.8566, 2.3522);
    static final Location TOKYO = new Location("Tokyo", "JP", 35.6762, 139.6503);
    static final LocalDate START = LocalDate.of(2026, 11, 15);
    static final String UPSTREAM_DETAIL = "The weather provider is temporarily unavailable.";

    private final Set<String> clients = ConcurrentHashMap.newKeySet();

    @AfterEach
    void deleteRows() {
        clients.forEach(c -> FavouriteRows.deleteAll(jdbc, c));
        clients.clear();
    }

    // ---------------------------------------------------------------- helpers

    private String newClient() {
        String c = "it-ffc-real-" + UUID.randomUUID();
        clients.add(c);
        return c;
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String clientId, String body,
                                        Map<String, String> extra) {
        HttpHeaders h = new HttpHeaders();
        if (clientId != null) {
            h.set("X-Client-Id", clientId);
        }
        if (body != null) {
            h.set(HttpHeaders.CONTENT_TYPE, "application/json");
        }
        extra.forEach(h::set);
        return http.exchange(URI.create(path), method, new HttpEntity<>(body, h), String.class);
    }

    private ResponseEntity<String> forecast(String clientId, String query, Map<String, String> extra) {
        return send(HttpMethod.GET, FORECAST + query, clientId, null, extra);
    }

    private JsonNode items(ResponseEntity<String> res) {
        assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(200);
        return json(res).get("items");
    }

    private String createLondon(String client, String label) {
        String body = label == null ? "{\"city\": \"London\"}"
                : "{\"city\": \"London\", \"label\": \"" + label + "\"}";
        ResponseEntity<String> created = send(HttpMethod.POST, "/api/v1/favourites", client, body, Map.of());
        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        return json(created).get("id").asText();
    }

    /** London through the API (1 GEOCODING against the fake), Paris and Tokyo inserted directly; returns ids in order. */
    private List<String> seedLondonParisTokyo(String client, String londonLabel) {
        List<String> ids = new ArrayList<>();
        ids.add(createLondon(client, londonLabel));
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

    private static String plain(double d) {
        return BigDecimal.valueOf(d).toPlainString();
    }

    private static String coords(Location l) {
        return plain(l.latitude()) + "/" + plain(l.longitude());
    }

    private static Set<String> coordinates(List<RecordedRequest> requests) {
        Set<String> out = new HashSet<>();
        requests.forEach(r -> out.add(r.param("latitude") + "/" + r.param("longitude")));
        return out;
    }

    private static boolean isLatitude(RecordedRequest r, String latitude) {
        return latitude.equals(r.params().getOrDefault("latitude", List.of()).stream().findFirst().orElse(null));
    }

    private static void assertDays(JsonNode forecast, int n) {
        JsonNode days = forecast.get("days");
        assertThat(days).hasSize(n);
        for (int i = 0; i < n; i++) {
            assertThat(days.get(i).get("date").asText()).isEqualTo(START.plusDays(i).toString());
        }
    }

    // ---------------------------------------------------------------- AC-9, AC-10, AC-11

    @Test
    @DisplayName("AC-9/AC-11: cold cache: 3 forecast items after exactly 3 DAILY (stored coordinates, start 2026-11-15, end 2026-11-19), 0 GEOCODING, 0 CURRENT; exactly one forecast key per location with TTL in (0, 1h]; no client id/label/city in keys")
    void ac9_ac11_storedCoordinatesAndKeys() {
        String client = newClient();
        List<String> ids = seedLondonParisTokyo(client, "LBL-SECRET9");
        Set<String> keysBefore = runKeys();
        fake.reset();

        JsonNode items = items(forecast(client, "?days=5", Map.of()));

        // AC-9
        assertThat(ids(items)).containsExactlyElementsOf(ids);
        items.forEach(i -> {
            assertThat(i.has("forecast")).as(i.toString()).isTrue();
            assertThat(i.has("error")).isFalse();
            assertThat(i.at("/forecast/location")).isEqualTo(i.get("location"));
            assertThat(i.at("/forecast/units").asText()).isEqualTo("metric");
            assertDays(i.get("forecast"), 5);
        });
        List<RecordedRequest> daily = received(Route.DAILY);
        assertThat(daily).hasSize(3);
        assertThat(received(Route.GEOCODING)).isEmpty();
        assertThat(received(Route.CURRENT)).isEmpty();
        assertThat(coordinates(daily)).containsExactlyInAnyOrder(coords(LONDON), coords(PARIS), coords(TOKYO));
        assertThat(daily).allSatisfy(r -> {
            assertThat(r.param("start_date")).isEqualTo("2026-11-15");
            assertThat(r.param("end_date")).isEqualTo("2026-11-19");
        });

        // AC-11
        Set<String> added = new HashSet<>(runKeys());
        added.removeAll(keysBefore);
        assertThat(added).containsExactlyInAnyOrder(cacheKeys.forecast(LONDON, START, 5),
                cacheKeys.forecast(PARIS, START, 5), cacheKeys.forecast(TOKYO, START, 5));
        Pattern shape = Pattern.compile(Pattern.quote(cacheKeys.keyPrefix())
                + ":forecast:v1:-?[0-9.]+:-?[0-9.]+:2026-11-15:5");
        for (String key : added) {
            assertThat(key).matches(shape);
            assertThat(pttl(key)).as(key).isGreaterThan(0).isLessThanOrEqualTo(Duration.ofHours(1).toMillis());
            assertThat(key.toLowerCase()).doesNotContain(client.toLowerCase()).doesNotContain("lbl-secret9")
                    .doesNotContain("london").doesNotContain("paris").doesNotContain("tokyo");
        }
        assertThat(added).noneMatch(k -> k.contains(":current:") || k.contains(":geocoding:"));
        assertThat(runKeys()).allSatisfy(k -> assertThat(k).matches(
                Pattern.quote(cacheKeys.keyPrefix()) + ":(forecast|geocoding):v1:.*"));
    }

    @Test
    @DisplayName("AC-10: warm repeat (days=5) makes 0 upstream requests with an equal body; /weather/forecast?city=London&days=5 reuses the entry (0 DAILY); days=4 makes 1 DAILY per favourite; reverse: a /weather/forecast?days=3 entry is served to /favourites/forecast?days=3")
    void ac10_warmCacheSharedBothWays() {
        String client = newClient();
        seedLondonParisTokyo(client, null);
        fake.reset();
        ResponseEntity<String> first = forecast(client, "?days=5", Map.of());
        JsonNode firstItems = items(first);
        assertThat(received(Route.DAILY)).hasSize(3);

        // warm repeat within the TTL
        fake.reset();
        CLOCK.set(T0.plus(Duration.ofMinutes(30)));
        ResponseEntity<String> warm = forecast(client, "?days=5", Map.of());
        assertThat(fake.requests()).as("no upstream request of any kind").isEmpty();
        assertThat(json(warm)).isEqualTo(json(first));

        // /weather/forecast reuses the favourite's forecast entry
        fake.reset();
        ResponseEntity<String> byCity = get("/api/v1/weather/forecast?city=London&days=5");
        assertThat(byCity.getStatusCode().value()).isEqualTo(200);
        assertThat(received(Route.DAILY)).as("/weather/forecast reuses the stored-coordinates entry").isEmpty();
        assertThat(json(byCity)).isEqualTo(firstItems.get(0).get("forecast"));

        // days is part of the key
        fake.reset();
        JsonNode four = items(forecast(client, "?days=4", Map.of()));
        assertThat(received(Route.DAILY)).hasSize(3);
        assertThat(received(Route.DAILY)).allSatisfy(r -> assertThat(r.param("end_date")).isEqualTo("2026-11-18"));
        four.forEach(i -> assertDays(i.get("forecast"), 4));

        // reverse direction: fresh client, empty cache
        CLOCK.set(T0);
        deleteRunRedisKeys();
        fake.reset();
        ResponseEntity<String> seeded = get("/api/v1/weather/forecast?city=London&days=3");
        assertThat(seeded.getStatusCode().value()).isEqualTo(200);
        assertThat(received(Route.DAILY)).hasSize(1);
        String fresh = newClient();
        createLondon(fresh, null);
        fake.reset();

        JsonNode reverse = items(forecast(fresh, "?days=3", Map.of()));

        assertThat(received(Route.DAILY)).as("served from the /weather/forecast entry").isEmpty();
        assertThat(reverse).singleElement().satisfies(i -> assertThat(i.get("forecast")).isEqualTo(json(seeded)));
    }

    // ---------------------------------------------------------------- AC-14, AC-16

    @Test
    @DisplayName("AC-14/AC-16 (real path): DAILY 500 for Paris's latitude: 200, Paris UPSTREAM_UNAVAILABLE; endpoint and adapter WARNs carry it-ffc-14; no Paris forecast key; the next request makes exactly 1 DAILY, for Paris")
    void ac14_ac16_realPartialFailure(CapturedOutput output) {
        String requestId = "it-ffc-14";
        String client = newClient();
        List<String> ids = seedLondonParisTokyo(client, "Home");
        fake.reset();
        fake.stub(Route.DAILY, r -> isLatitude(r, "48.8566")
                ? Response.status(500, "{\"error\":true}") : FakeOpenMeteo.dailyFor(r));

        int mark = output.getAll().length();
        JsonNode items = items(forecast(client, "", Map.of("X-Request-Id", requestId)));
        List<String> lines = output.getAll().substring(mark).lines().toList();

        assertThat(ids(items)).containsExactlyElementsOf(ids);
        assertThat(items.get(0).has("forecast")).isTrue();
        assertThat(items.get(0).get("label").asText()).isEqualTo("Home");
        assertThat(items.get(1).has("forecast")).isFalse();
        assertThat(items.get(1).get("error")).isEqualTo(
                JSON.createObjectNode().put("code", "UPSTREAM_UNAVAILABLE").put("detail", UPSTREAM_DETAIL));
        assertThat(items.get(1).get("location").get("latitude").asDouble()).isEqualTo(PARIS.latitude());
        assertThat(items.get(2).has("forecast")).isTrue();
        assertThat(received(Route.DAILY)).hasSize(3);

        List<String> endpointWarns = lines.stream().filter(l -> l.contains("Favourites forecast partial failure")).toList();
        assertThat(endpointWarns).singleElement().satisfies(l -> assertThat(l)
                .contains("WARN").contains("[requestId=" + requestId + "]")
                .contains("failed=1 total=3 timedOut=0")
                .doesNotContain("Paris").doesNotContain("48.8566").doesNotContain(client));
        assertThat(lines).noneMatch(l -> l.contains("Favourites weather partial failure"));
        List<String> adapterWarns = lines.stream().filter(l -> l.contains("Upstream call failed")).toList();
        assertThat(adapterWarns).singleElement().satisfies(l -> assertThat(l)
                .contains("[requestId=" + requestId + "]").contains("outcome=status=500"));

        // AC-16: failures are not cached; London and Tokyo come from the cache
        assertThat(runKeys()).doesNotContain(cacheKeys.forecast(PARIS, START, 5))
                .contains(cacheKeys.forecast(LONDON, START, 5), cacheKeys.forecast(TOKYO, START, 5));
        fake.reset();
        JsonNode again = items(forecast(client, "", Map.of()));

        assertThat(again).allSatisfy(i -> assertThat(i.has("forecast")).as(i.toString()).isTrue());
        assertThat(received(Route.DAILY)).singleElement()
                .satisfies(r -> assertThat(r.param("latitude")).isEqualTo("48.8566"));
        assertThat(fake.requests()).hasSize(1);
    }

    @Test
    @DisplayName("AC-15 (real path): every DAILY answers 500: 200 with 3 UPSTREAM_UNAVAILABLE items and no forecast key; after fake.reset() the same request gives 3 forecast items (3 DAILY)")
    void ac15_realAllFail() {
        String client = newClient();
        seedLondonParisTokyo(client, null);
        fake.reset();
        fake.stub(Route.DAILY, Response.status(500, "{\"error\":true}"));

        ResponseEntity<String> res = forecast(client, "", Map.of());
        assertThat(res.getStatusCode().value()).as("not 503").isEqualTo(200);
        JsonNode failed = items(res);

        assertThat(failed).hasSize(3).allSatisfy(i -> {
            assertThat(i.has("forecast")).isFalse();
            assertThat(i.at("/error/code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
        });
        assertThat(runKeys()).noneMatch(k -> k.contains(":forecast:"));

        fake.reset();
        JsonNode ok = items(forecast(client, "", Map.of()));
        assertThat(ok).hasSize(3).allSatisfy(i -> assertThat(i.has("forecast")).isTrue());
        assertThat(received(Route.DAILY)).hasSize(3);
    }

    @Test
    @DisplayName("AC-14 (edge, real path): a malformed 200 DAILY body (wrong number of days) for one item is UPSTREAM_UNAVAILABLE for that item only and is not cached")
    void ac14_edge_malformedDailyBody() {
        String client = newClient();
        seedLondonParisTokyo(client, null);
        fake.reset();
        fake.stub(Route.DAILY, r -> isLatitude(r, "35.6762")
                ? Response.ok(FakeOpenMeteo.json(FakeOpenMeteo.daily(START, 2))) : FakeOpenMeteo.dailyFor(r));

        JsonNode items = items(forecast(client, "?days=5", Map.of()));

        assertThat(items.get(0).has("forecast")).isTrue();
        assertThat(items.get(1).has("forecast")).isTrue();
        assertThat(items.get(2).at("/error/code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(runKeys()).doesNotContain(cacheKeys.forecast(TOKYO, START, 5));
    }

    // ---------------------------------------------------------------- AC-2 (real)

    @Test
    @DisplayName("AC-2 (real path): DAILY responses complete in reverse order (P, then L, then T; latch-driven); the items are still in stored order T, L, P")
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
        InFlightResponder responder = InFlightResponder.daily(r -> isLatitude(r, "48.8566") ? Duration.ZERO : step)
                .gate(3)
                .before((self, r) -> {
                    if (isLatitude(r, "35.6762")) {
                        self.awaitCompleted("51.50853", cap);
                    } else if (isLatitude(r, "51.50853")) {
                        self.awaitCompleted("48.8566", cap);
                    }
                });
        fake.stub(Route.DAILY, responder);

        JsonNode items = items(forecast(client, "", Map.of()));

        assertThat(responder.awaitIdle(cap)).isTrue();
        assertThat(responder.gateTimedOut()).as("all three calls were in flight together").isFalse();
        assertThat(responder.completedLatitudes()).containsExactly("48.8566", "51.50853", "35.6762");
        assertThat(ids(items)).containsExactly(t, l, p);
        assertThat(items).allSatisfy(i -> assertThat(i.has("forecast")).as(i.toString()).isTrue());
    }

    // ---------------------------------------------------------------- AC-19

    @Test
    @DisplayName("AC-19: max-concurrency=4, 20 cold favourites, 200 ms per DAILY, days=7: 20 forecast items (7 days) in stored order, exactly 20 DAILY requests, max in flight in [2, 4] (latch), under 4 s after a warm-up")
    void ac19_boundedParallelism() {
        // Warm-up (not measured): one item through the whole path, so lazy initialisation is excluded.
        String warm = newClient();
        FavouriteRows.insert(jdbc, warm, "Warmup", "Warmup", "XX", -10.5, -20.5, 1, T0);
        items(forecast(warm, "?days=7", Map.of()));
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
        InFlightResponder responder = InFlightResponder.daily(r -> Duration.ofMillis(200)).gate(2);
        fake.stub(Route.DAILY, responder);

        long start = System.nanoTime();
        ResponseEntity<String> res = forecast(client, "?days=7", Map.of());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(responder.awaitIdle(Duration.ofSeconds(5))).isTrue();
        JsonNode items = items(res);
        assertThat(ids(items)).containsExactlyElementsOf(ids);
        for (int i = 0; i < 20; i++) {
            assertThat(items.get(i).has("forecast")).as(items.get(i).toString()).isTrue();
            assertThat(items.get(i).at("/forecast/location/latitude").asDouble()).isEqualTo(latitudes.get(i));
            assertDays(items.get(i).get("forecast"), 7);
        }
        assertThat(received(Route.DAILY)).hasSize(20);
        assertThat(received(Route.GEOCODING)).isEmpty();
        assertThat(responder.started()).isEqualTo(20);
        assertThat(responder.gateTimedOut()).as("two calls were in flight at once").isFalse();
        assertThat(responder.maxInFlight()).isBetween(2, 4);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(4));
    }

    // ---------------------------------------------------------------- AC-27 (quota regression)

    @Test
    @DisplayName("AC-27: /weather/forecast still costs 1 GEOCODING + 1 DAILY cold and 0 warm; /favourites/weather still costs 1 CURRENT per cold favourite and 0 DAILY")
    void ac27_quotaRegression() {
        assertThat(get("/api/v1/weather/forecast?city=London&days=2").getStatusCode().value()).isEqualTo(200);
        assertThat(received(Route.GEOCODING)).hasSize(1);
        assertThat(received(Route.DAILY)).hasSize(1);
        assertThat(received(Route.CURRENT)).isEmpty();
        fake.reset();
        assertThat(get("/api/v1/weather/forecast?city=London&days=2").getStatusCode().value()).isEqualTo(200);
        assertThat(fake.requests()).isEmpty();

        String client = newClient();
        seedLondonParisTokyo(client, null);
        fake.reset();
        JsonNode weather = items(send(HttpMethod.GET, "/api/v1/favourites/weather", client, null, Map.of()));
        assertThat(weather).hasSize(3).allSatisfy(i -> assertThat(i.has("weather")).isTrue());
        assertThat(received(Route.CURRENT)).hasSize(3);
        assertThat(received(Route.DAILY)).isEmpty();
        assertThat(received(Route.GEOCODING)).isEmpty();
    }
}
