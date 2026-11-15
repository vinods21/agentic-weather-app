package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.weather.provider.LocationNotFoundException;
import com.example.weather.provider.WeatherProviderUnavailableException;
import com.example.weather.support.FavouriteRows;
import com.example.weather.support.FavouritesIntegrationTestBase;
import com.example.weather.weather.Location;
import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * GET /api/v1/favourites/forecast with the stub provider (spy), sharing the
 * {@link FavouritesIntegrationTestBase} context (no extra configuration): AC-1..AC-8, AC-14/15
 * (stub path), AC-17, AC-18, AC-23, AC-26. F(city, units, days) is fetched from /weather/forecast
 * in the same test at the same clock instant.
 */
class FavouritesForecastIT extends FavouritesIntegrationTestBase {

    static final String FORECAST = BASE + "/forecast";
    static final String CLIENT_ID_ERROR = "X-Client-Id: must match ^[A-Za-z0-9._-]{1,64}$";
    static final String UPSTREAM_DETAIL = "The weather provider is temporarily unavailable.";
    static final String NOT_FOUND_DETAIL = "No weather data is available for this location.";
    static final double PARIS_LAT = 48.8566;
    static final LocalDate START = LocalDate.of(2026, 11, 15);

    @Autowired
    DataSource dataSource;

    private String client;
    private JsonNode london;
    private JsonNode paris;
    private JsonNode tokyo;

    @BeforeEach
    void createLondonParisTokyo() {
        client = newClientId();
        ResponseEntity<String> home = post(client, "{\"city\": \"London\", \"label\": \"Home\"}");
        assertThat(home.getStatusCode().value()).isEqualTo(201);
        london = json(home);
        CLOCK.set(T0.plusSeconds(1));
        paris = create(client, "Paris");
        CLOCK.set(T0.plusSeconds(2));
        tokyo = create(client, "Tokyo");
        CLOCK.set(T0);
        clearInvocations(provider);
    }

    // ---------------------------------------------------------------- helpers

    private ResponseEntity<String> forecast(String clientId, String query) {
        return send(HttpMethod.GET, FORECAST + query, clientId, null, Map.of());
    }

    private JsonNode okItems(ResponseEntity<String> res) {
        assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(200);
        JsonNode body = json(res);
        assertThat(fieldNames(body)).containsExactly("items");
        return body.get("items");
    }

    /** F(city, units, days): the 200 body of /weather/forecast; a null units/days is left out. */
    private JsonNode f(String city, String units, Integer days) {
        StringBuilder q = new StringBuilder("/api/v1/weather/forecast?city=").append(city.replace(" ", "%20"));
        if (units != null) {
            q.append("&units=").append(units);
        }
        if (days != null) {
            q.append("&days=").append(days);
        }
        ResponseEntity<String> res = get(q.toString());
        assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(200);
        return json(res);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> ids(JsonNode items) {
        List<String> out = new ArrayList<>();
        items.forEach(i -> out.add(i.get("id").asText()));
        return out;
    }

    private static Location isParis() {
        return argThat(l -> l != null && l.latitude() == PARIS_LAT);
    }

    private static void assertDays(JsonNode forecast, int n) {
        JsonNode days = forecast.get("days");
        assertThat(days).hasSize(n);
        for (int i = 0; i < n; i++) {
            assertThat(days.get(i).get("date").asText()).isEqualTo(START.plusDays(i).toString());
        }
    }

    private static void assertForecastItem(JsonNode item, JsonNode favourite, JsonNode expectedForecast) {
        assertThat(item.get("id")).isEqualTo(favourite.get("id"));
        assertThat(item.get("city")).isEqualTo(favourite.get("city"));
        assertThat(item.get("location")).isEqualTo(favourite.get("location"));
        assertThat(item.get("label")).isEqualTo(favourite.get("label"));
        assertThat(item.get("forecast")).isEqualTo(expectedForecast);
        assertThat(item.has("error")).as("no error property").isFalse();
    }

    private static void assertErrorItem(JsonNode item, JsonNode favourite, String code, String detail) {
        assertThat(item.get("id")).isEqualTo(favourite.get("id"));
        assertThat(item.get("city")).isEqualTo(favourite.get("city"));
        assertThat(item.get("location")).isEqualTo(favourite.get("location"));
        assertThat(item.get("label")).isEqualTo(favourite.get("label"));
        assertThat(item.has("forecast")).as("no forecast property").isFalse();
        assertThat(item.get("error")).isEqualTo(JSON.createObjectNode().put("code", code).put("detail", detail));
    }

    // ---------------------------------------------------------------- AC-1, AC-26

    @Test
    @DisplayName("AC-1/AC-26: 200 application/json with X-Request-Id and 3 items London, Paris, Tokyo; id/city/location as listed, label only for London, forecast = F(city, metric, 5) from 2026-11-15; no row changed")
    void ac1_happyPath() {
        List<Map<String, Object>> before = rows(client);
        JsonNode listed = json(list(client)).get("items");

        ResponseEntity<String> res = forecast(client, "");

        assertThat(String.valueOf(res.getHeaders().getContentType())).startsWith("application/json");
        assertThat(res.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        JsonNode items = okItems(res);
        assertThat(items).hasSize(3);
        assertThat(ids(items)).containsExactly(london.get("id").asText(), paris.get("id").asText(),
                tokyo.get("id").asText());
        verify(provider, times(3)).forecastAtLocation(any(), eq(START), eq(5));
        verify(provider, never()).forecast(any(), any(), anyInt());

        assertThat(fieldNames(items.get(0))).containsExactlyInAnyOrder("id", "city", "label", "location", "forecast");
        assertThat(items.get(0).get("label").asText()).isEqualTo("Home");
        assertThat(fieldNames(items.get(1))).containsExactlyInAnyOrder("id", "city", "location", "forecast");
        assertThat(fieldNames(items.get(2))).containsExactlyInAnyOrder("id", "city", "location", "forecast");

        assertForecastItem(items.get(0), listed.get(0), f("London", "metric", 5));
        assertForecastItem(items.get(1), listed.get(1), f("Paris", "metric", 5));
        assertForecastItem(items.get(2), listed.get(2), f("Tokyo", "metric", 5));
        items.forEach(i -> {
            assertThat(i.at("/forecast/units").asText()).isEqualTo("metric");
            assertThat(i.at("/forecast/location")).isEqualTo(i.get("location"));
            assertDays(i.get("forecast"), 5);
        });

        assertThat(rows(client)).as("AC-26: no row changed").isEqualTo(before);
    }

    // ---------------------------------------------------------------- AC-2

    @Test
    @DisplayName("AC-2: after a reorder to T, L, P the items are in order T, L, P")
    void ac2_storedOrder() {
        String l = london.get("id").asText();
        String p = paris.get("id").asText();
        String t = tokyo.get("id").asText();
        assertThat(reorder(client, reorderBody(t, l, p)).getStatusCode().value()).isEqualTo(200);

        assertThat(ids(okItems(forecast(client, "")))).containsExactly(t, l, p);
    }

    // ---------------------------------------------------------------- AC-3

    @ParameterizedTest(name = "days={0}")
    @ValueSource(ints = {1, 3, 7})
    @DisplayName("AC-3: ?days=n gives forecast = F(city, metric, n) with exactly n days from 2026-11-15")
    void ac3_days(int n) {
        String c = newClientId();
        JsonNode l = create(c, "London");
        JsonNode p = create(c, "Paris");

        JsonNode items = okItems(forecast(c, "?days=" + n));

        assertThat(items).hasSize(2);
        assertForecastItem(items.get(0), l, f("London", "metric", n));
        assertForecastItem(items.get(1), p, f("Paris", "metric", n));
        items.forEach(i -> assertDays(i.get("forecast"), n));
    }

    @ParameterizedTest(name = "query=''{0}''")
    @ValueSource(strings = {"", "?days=", "?days=5"})
    @DisplayName("AC-3: no days parameter and an empty days= both give 5 days (= F(city, metric, omitted days))")
    void ac3_defaultDays(String query) {
        JsonNode items = okItems(forecast(client, query));

        assertThat(items).hasSize(3);
        assertThat(items.get(0).get("forecast")).isEqualTo(f("London", null, null));
        items.forEach(i -> assertDays(i.get("forecast"), 5));
    }

    @ParameterizedTest(name = "days={0}")
    @ValueSource(strings = {"+3", "%2B3", "03", "%203%20"})
    @DisplayName("AC-3: ?days=+3, ?days=03 and ?days=%203%20 give 3 days, as on /weather/forecast")
    void ac3_normalisedDays(String raw) {
        JsonNode items = okItems(forecast(client, "?days=" + raw));

        ResponseEntity<String> reference = get("/api/v1/weather/forecast?city=Paris&days=" + raw);
        assertThat(reference.getStatusCode().value()).isEqualTo(200);
        assertThat(items.get(1).get("forecast")).isEqualTo(json(reference));
        items.forEach(i -> assertDays(i.get("forecast"), 3));
    }

    // ---------------------------------------------------------------- AC-4

    @Test
    @DisplayName("AC-4: units=imperial&days=3 gives F(city, imperial, 3); units=metric and no units both give metric")
    void ac4_units() {
        JsonNode imperial = okItems(forecast(client, "?units=imperial&days=3"));
        assertThat(imperial.get(0).get("forecast")).isEqualTo(f("London", "imperial", 3));
        assertThat(imperial.get(1).get("forecast")).isEqualTo(f("Paris", "imperial", 3));
        assertThat(imperial.get(2).get("forecast")).isEqualTo(f("Tokyo", "imperial", 3));
        imperial.forEach(i -> {
            assertThat(i.at("/forecast/units").asText()).isEqualTo("imperial");
            assertDays(i.get("forecast"), 3);
        });

        JsonNode metric = okItems(forecast(client, "?units=metric&days=3"));
        JsonNode none = okItems(forecast(client, "?days=3"));
        assertThat(metric).isEqualTo(none);
        assertThat(metric.get(0).get("forecast")).isEqualTo(f("London", "metric", 3));
        assertThat(metric.get(0).get("forecast")).isNotEqualTo(imperial.get(0).get("forecast"));
        metric.forEach(i -> assertThat(i.at("/forecast/units").asText()).isEqualTo("metric"));
    }

    // ---------------------------------------------------------------- AC-5

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"units=IMPERIAL", "units=kelvin", "units=", "days=0", "days=8", "days=abc",
        "days=3.5", "days=0x3", "days=99999999999", "units=kelvin&days=99"})
    @DisplayName("AC-5: invalid units/days give the same status and errors (as a set) as /weather/forecast?city=London&<qs>; provider not called")
    void ac5_validationParity(String qs) {
        ResponseEntity<String> res = forecast(client, "?" + qs);
        verifyNoInteractions(provider);

        JsonNode problem = assertProblem(res, 400, "VALIDATION_FAILED");
        ResponseEntity<String> refRes = get("/api/v1/weather/forecast?city=London&" + qs);
        JsonNode reference = json(refRes);
        assertThat(res.getStatusCode()).isEqualTo(refRes.getStatusCode());
        assertThat(problem.get("status")).isEqualTo(reference.get("status"));
        assertThat(problem.get("code")).isEqualTo(reference.get("code"));
        assertThat(new HashSet<>(errors(problem))).isEqualTo(new HashSet<>(errors(reference)));
    }

    @Test
    @DisplayName("AC-5: the exact messages: units 'must be one of: metric, imperial'; days 'must be between 1 and 7' / 'must be an integer between 1 and 7'; units=kelvin&days=99 reports both")
    void ac5_exactMessages() {
        assertThat(errors(assertProblem(forecast(client, "?units=kelvin"), 400, "VALIDATION_FAILED")))
                .containsExactly("units: must be one of: metric, imperial");
        assertThat(errors(assertProblem(forecast(client, "?days=8"), 400, "VALIDATION_FAILED")))
                .containsExactly("days: must be between 1 and 7");
        assertThat(errors(assertProblem(forecast(client, "?days=0x3"), 400, "VALIDATION_FAILED")))
                .containsExactly("days: must be an integer between 1 and 7");
        assertThat(errors(assertProblem(forecast(client, "?units=kelvin&days=99"), 400, "VALIDATION_FAILED")))
                .containsExactlyInAnyOrder("units: must be one of: metric, imperial", "days: must be between 1 and 7");
        verifyNoInteractions(provider);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"page=2", "city=Atlantis", "page=2&foo=bar&city="})
    @DisplayName("AC-5: unknown query parameters are ignored: the response equals the one without them")
    void ac5_unknownParametersIgnored(String qs) {
        JsonNode without = okItems(forecast(client, ""));

        assertThat(okItems(forecast(client, "?" + qs))).isEqualTo(without);
    }

    // ---------------------------------------------------------------- AC-6

    @ParameterizedTest(name = "query=''{0}''")
    @ValueSource(strings = {"", "?days=3", "?units=imperial&days=7"})
    @DisplayName("AC-6: a client with no favourites gets 200 {\"items\": []} (with or without valid days/units) and the provider is not called")
    void ac6_emptyList(String query) {
        ResponseEntity<String> res = forecast(newClientId(), query);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(json(res)).isEqualTo(JSON.createObjectNode().set("items", JSON.createArrayNode()));
        verifyNoInteractions(provider);
    }

    // ---------------------------------------------------------------- AC-7

    static Stream<Arguments> invalidClientIds() {
        return Stream.of(
                Arguments.of("<missing>", null),
                Arguments.of("empty", ""),
                Arguments.of("65 chars", "a".repeat(65)),
                Arguments.of("'a b'", "a b"),
                Arguments.of("'a/b'", "a/b"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidClientIds")
    @DisplayName("AC-7: a missing, empty, 65-char or ill-formed X-Client-Id is 400 VALIDATION_FAILED with the X-Client-Id error; provider not called")
    void ac7_invalidClientId(String label, String header) {
        JsonNode problem = assertProblem(forecast(header, ""), 400, "VALIDATION_FAILED");

        assertThat(errors(problem)).containsExactly(CLIENT_ID_ERROR);
        verifyNoInteractions(provider);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidClientIds")
    @DisplayName("AC-7: an invalid X-Client-Id together with units=kelvin&days=0 still reports the X-Client-Id error; provider not called")
    void ac7_invalidClientIdAndQuery(String label, String header) {
        JsonNode problem = assertProblem(forecast(header, "?units=kelvin&days=0"), 400, "VALIDATION_FAILED");

        assertThat(errors(problem)).contains(CLIENT_ID_ERROR);
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("AC-7: a 64-character valid X-Client-Id is accepted")
    void ac7_sixtyFourChars() {
        String id64 = track("it-ffc-64-" + "x".repeat(54));
        assertThat(id64).hasSize(64);
        create(id64, "Sydney");

        JsonNode items = okItems(forecast(id64, ""));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).at("/forecast/location/name").asText()).isEqualTo("Sydney");
    }

    // ---------------------------------------------------------------- AC-8

    @Test
    @DisplayName("AC-8: client B sees only its own favourite (Tokyo); a client without favourites gets [] although A has some")
    void ac8_isolation() {
        String a = newClientId();
        create(a, "London");
        create(a, "Paris");
        String b = newClientId();
        JsonNode bTokyo = create(b, "Tokyo");

        JsonNode items = okItems(forecast(b, ""));
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("id")).isEqualTo(bTokyo.get("id"));
        assertThat(items.get(0).get("city").asText()).isEqualTo("Tokyo");

        assertThat(okItems(forecast(newClientId(), ""))).isEmpty();
        assertThat(okItems(forecast(a, ""))).hasSize(2);
    }

    // ---------------------------------------------------------------- AC-14, AC-15, AC-26

    @Test
    @DisplayName("AC-14/AC-26 (stub path): Paris's lookup throws WeatherProviderUnavailableException: 200, 3 items in order, Paris has only the UPSTREAM_UNAVAILABLE error, the others F(city, metric, 5); no row changed")
    void ac14_oneItemFails() {
        List<Map<String, Object>> before = rows(client);
        doThrow(new WeatherProviderUnavailableException("Open-Meteo forecast call failed: status=500", null))
                .when(provider).forecastAtLocation(isParis(), any(), anyInt());

        JsonNode items = okItems(forecast(client, ""));

        assertThat(items).hasSize(3);
        assertForecastItem(items.get(0), london, f("London", "metric", 5));
        assertErrorItem(items.get(1), paris, "UPSTREAM_UNAVAILABLE", UPSTREAM_DETAIL);
        assertThat(fieldNames(items.get(1))).containsExactlyInAnyOrder("id", "city", "location", "error");
        assertForecastItem(items.get(2), tokyo, f("Tokyo", "metric", 5));
        assertThat(rows(client)).as("AC-26: no row changed").isEqualTo(before);
    }

    @Test
    @DisplayName("AC-14 (edge): a failing labelled item keeps its label next to the error")
    void ac14_edge_failedItemKeepsLabel() {
        doThrow(new WeatherProviderUnavailableException("x", null))
                .when(provider).forecastAtLocation(argThat(l -> l != null && l.name().equals("London")), any(), anyInt());

        JsonNode items = okItems(forecast(client, ""));

        assertErrorItem(items.get(0), london, "UPSTREAM_UNAVAILABLE", UPSTREAM_DETAIL);
        assertThat(items.get(0).get("label").asText()).isEqualTo("Home");
        assertThat(items.get(1).has("forecast")).isTrue();
    }

    @Test
    @DisplayName("AC-15/AC-26 (stub path): every lookup fails: still 200 (not 503) with 3 UPSTREAM_UNAVAILABLE items and no forecast; after removing the failure the same request gives 3 forecast items; no row changed")
    void ac15_allItemsFail() {
        List<Map<String, Object>> before = rows(client);
        doThrow(new WeatherProviderUnavailableException("x", null))
                .when(provider).forecastAtLocation(any(), any(), anyInt());

        ResponseEntity<String> res = forecast(client, "");
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode failed = okItems(res);

        assertThat(failed).hasSize(3);
        assertErrorItem(failed.get(0), london, "UPSTREAM_UNAVAILABLE", UPSTREAM_DETAIL);
        assertErrorItem(failed.get(1), paris, "UPSTREAM_UNAVAILABLE", UPSTREAM_DETAIL);
        assertErrorItem(failed.get(2), tokyo, "UPSTREAM_UNAVAILABLE", UPSTREAM_DETAIL);

        doCallRealMethod().when(provider).forecastAtLocation(any(), any(), anyInt());
        JsonNode recovered = okItems(forecast(client, ""));

        assertThat(recovered).hasSize(3).allSatisfy(i -> {
            assertThat(i.has("forecast")).isTrue();
            assertThat(i.has("error")).isFalse();
        });
        assertThat(rows(client)).as("AC-26: no row changed").isEqualTo(before);
    }

    // ---------------------------------------------------------------- AC-17

    @Test
    @DisplayName("AC-17: Paris's lookup throws LocationNotFoundException: that item is LOCATION_NOT_FOUND with a fixed detail without the city; the others are unaffected; 200")
    void ac17_notFoundFromProvider() {
        doThrow(new LocationNotFoundException("Paris")).when(provider).forecastAtLocation(isParis(), any(), anyInt());

        JsonNode items = okItems(forecast(client, ""));

        assertForecastItem(items.get(0), london, f("London", "metric", 5));
        assertErrorItem(items.get(1), paris, "LOCATION_NOT_FOUND", NOT_FOUND_DETAIL);
        assertThat(items.get(1).at("/error/detail").asText()).doesNotContain("Paris");
        assertForecastItem(items.get(2), tokyo, f("Tokyo", "metric", 5));
    }

    @Test
    @DisplayName("AC-17: a row inserted directly at 0.0/0.0 (unknown to the stub) is LOCATION_NOT_FOUND; the API-created item still has its forecast")
    void ac17_notFoundForUnknownStoredLocation() {
        String c = newClientId();
        JsonNode sydney = create(c, "Sydney");
        UUID atlantis = FavouriteRows.insert(jdbc, c, "Atlantis", "Atlantis", "XX", 0.0, 0.0, 2, T0);

        JsonNode items = okItems(forecast(c, ""));

        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("id")).isEqualTo(sydney.get("id"));
        assertThat(items.get(0).get("forecast")).isEqualTo(f("Sydney", "metric", 5));
        assertThat(items.get(1).get("id").asText()).isEqualTo(atlantis.toString());
        assertThat(items.get(1).get("error"))
                .isEqualTo(JSON.createObjectNode().put("code", "LOCATION_NOT_FOUND").put("detail", NOT_FOUND_DETAIL));
        assertThat(items.get(1).has("forecast")).isFalse();
        assertThat(items.get(1).at("/error/detail").asText()).doesNotContain("Atlantis");
    }

    @Test
    @DisplayName("AC-17 (edge): a row with a stub city's name but other coordinates is LOCATION_NOT_FOUND (lookup is by stored coordinates, never by name)")
    void ac17_edge_knownNameOtherCoordinates() {
        String c = newClientId();
        FavouriteRows.insert(jdbc, c, "London", "London", "GB", 51.5, -0.12, 1, T0);

        JsonNode items = okItems(forecast(c, ""));

        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.at("/error/code").asText()).isEqualTo("LOCATION_NOT_FOUND");
            assertThat(i.has("forecast")).isFalse();
        });
        verify(provider, never()).forecast(any(), any(), anyInt());
    }

    // ---------------------------------------------------------------- AC-18

    @Test
    @DisplayName("AC-18: an IllegalStateException in one lookup is 500 INTERNAL_ERROR (Problem Details, no message, class or stack trace in the body), not a per-item error")
    void ac18_unexpectedFailureIs500() {
        doThrow(new IllegalStateException("boom-SECRET18")).when(provider).forecastAtLocation(isParis(), any(), anyInt());

        ResponseEntity<String> res = forecast(client, "");

        JsonNode problem = assertProblem(res, 500, "INTERNAL_ERROR");
        assertThat(res.getBody()).doesNotContain("boom-SECRET18").doesNotContain("IllegalStateException")
                .doesNotContain("at com.example").doesNotContain("items");
        assertThat(problem.get("requestId").asText()).isEqualTo(res.getHeaders().getFirst("X-Request-Id"));
    }

    // ---------------------------------------------------------------- AC-23

    @Test
    @DisplayName("AC-23: GET /favourites/forecast is the new endpoint (never 400 field id / 404); POST and PUT are 405 with Allow containing GET")
    void ac23_routing() {
        ResponseEntity<String> ok = forecast(newClientId(), "");
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody()).doesNotContain("\"field\":\"id\"");
        assertThat(okItems(forecast(client, ""))).hasSize(3);

        for (HttpMethod m : List.of(HttpMethod.POST, HttpMethod.PUT)) {
            ResponseEntity<String> res = send(m, FORECAST, client, m == HttpMethod.PUT ? "{}" : null, Map.of());
            assertProblem(res, 405, "METHOD_NOT_ALLOWED");
            assertThat(res.getHeaders().getAllow()).as(m.name()).contains(HttpMethod.GET);
        }
        assertThat(rowCount(client)).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-23 (documented A-1): PATCH and DELETE /favourites/forecast hit {id} and are 400 on field id; nothing is changed")
    void ac23_patchAndDeleteHitId() {
        List<Map<String, Object>> before = rows(client);
        JsonNode patchProblem = assertProblem(send(HttpMethod.PATCH, FORECAST, client, "{\"label\":\"x\"}", Map.of()),
                400, "VALIDATION_FAILED");
        assertThat(patchProblem.path("errors").get(0).get("field").asText()).isEqualTo("id");
        JsonNode deleteProblem = assertProblem(send(HttpMethod.DELETE, FORECAST, client, null, Map.of()),
                400, "VALIDATION_FAILED");
        assertThat(deleteProblem.path("errors").get(0).get("field").asText()).isEqualTo("id");
        assertThat(rows(client)).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-23/AC-27: GET /favourites/weather is 200, GET /favourites/order is 400 field id, GET /favourites/<random uuid> is 404, as before")
    void ac23_neighboursUnchanged() {
        ResponseEntity<String> weather = send(HttpMethod.GET, BASE + "/weather", client, null, Map.of());
        assertThat(weather.getStatusCode().value()).isEqualTo(200);
        assertThat(json(weather).get("items")).hasSize(3).allSatisfy(i -> assertThat(i.has("weather")).isTrue());

        JsonNode order = assertProblem(send(HttpMethod.GET, BASE + "/order", client, null, Map.of()),
                400, "VALIDATION_FAILED");
        assertThat(order.path("errors").get(0).get("field").asText()).isEqualTo("id");

        assertProblem(getOne(client, UUID.randomUUID()), 404, "NOT_FOUND");
    }

    // ---------------------------------------------------------------- AC-26 (connections)

    @Test
    @DisplayName("AC-26: no transaction is active and no pool connection is checked out while the provider is called")
    void ac26_noConnectionHeldDuringProviderCalls() throws SQLException {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        List<String> observed = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            observed.add("tx=" + TransactionSynchronizationManager.isActualTransactionActive()
                    + " active=" + pool.getHikariPoolMXBean().getActiveConnections());
            return inv.callRealMethod();
        }).when(provider).forecastAtLocation(any(), any(), anyInt());

        okItems(forecast(client, "?days=7"));

        verify(provider, atLeastOnce()).forecastAtLocation(any(), any(), anyInt());
        assertThat(observed).hasSize(3).allMatch("tx=false active=0"::equals);
    }

    @Test
    @DisplayName("AC-26: after a mixed request (one success, one not-found, one upstream failure) every column of every row is unchanged")
    void ac26_rowsUnchangedOnMixedOutcome() {
        doThrow(new LocationNotFoundException("x")).when(provider).forecastAtLocation(isParis(), any(), anyInt());
        doThrow(new WeatherProviderUnavailableException("x", null))
                .when(provider).forecastAtLocation(argThat(l -> l != null && l.name().equals("Tokyo")), any(), anyInt());
        List<Map<String, Object>> before = rows(client);

        okItems(forecast(client, "?units=imperial&days=2"));

        assertThat(rows(client)).isEqualTo(before);
        Set<Object> positions = new HashSet<>();
        rows(client).forEach(r -> positions.add(r.get("position")));
        assertThat(positions).hasSize(3);
    }
}
