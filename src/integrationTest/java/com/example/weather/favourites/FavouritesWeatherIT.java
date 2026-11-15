package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * GET /api/v1/favourites/weather with the stub provider (spy): AC-1..AC-6, AC-11/12/14/15 (stub
 * path), AC-20 and AC-23. W(city, units) is fetched from /weather/current in the same test.
 */
class FavouritesWeatherIT extends FavouritesIntegrationTestBase {

    static final String WEATHER = BASE + "/weather";
    static final String CLIENT_ID_ERROR = "X-Client-Id: must match ^[A-Za-z0-9._-]{1,64}$";
    static final String UPSTREAM_DETAIL = "The weather provider is temporarily unavailable.";
    static final String NOT_FOUND_DETAIL = "No weather data is available for this location.";
    static final double PARIS_LAT = 48.8566;

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

    private ResponseEntity<String> weather(String clientId, String query) {
        return send(HttpMethod.GET, WEATHER + query, clientId, null, Map.of());
    }

    private JsonNode okItems(ResponseEntity<String> res) {
        assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(200);
        JsonNode body = json(res);
        assertThat(fieldNames(body)).containsExactly("items");
        return body.get("items");
    }

    /** W(city, units): the 200 body of /weather/current at the same clock instant. */
    private JsonNode w(String city, String units) {
        ResponseEntity<String> res = get("/api/v1/weather/current?city=" + city + "&units=" + units);
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

    private static void assertWeatherItem(JsonNode item, JsonNode favourite, JsonNode expectedWeather) {
        assertThat(item.get("id")).isEqualTo(favourite.get("id"));
        assertThat(item.get("city")).isEqualTo(favourite.get("city"));
        assertThat(item.get("location")).isEqualTo(favourite.get("location"));
        assertThat(item.get("label")).isEqualTo(favourite.get("label"));
        assertThat(item.get("weather")).isEqualTo(expectedWeather);
        assertThat(item.has("error")).as("no error property").isFalse();
    }

    private static void assertErrorItem(JsonNode item, JsonNode favourite, String code, String detail) {
        assertThat(item.get("id")).isEqualTo(favourite.get("id"));
        assertThat(item.get("city")).isEqualTo(favourite.get("city"));
        assertThat(item.get("location")).isEqualTo(favourite.get("location"));
        assertThat(item.get("label")).isEqualTo(favourite.get("label"));
        assertThat(item.has("weather")).as("no weather property").isFalse();
        assertThat(item.get("error")).isEqualTo(JSON.createObjectNode().put("code", code).put("detail", detail));
    }

    // ---------------------------------------------------------------- AC-1, AC-23

    @Test
    @DisplayName("AC-1/AC-23: 200 application/json with X-Request-Id and 3 items in order London, Paris, Tokyo; id/city/location as listed, label only for London, weather = W(city, metric); no row changed")
    void ac1_happyPath() {
        List<Map<String, Object>> before = rows(client);
        JsonNode listed = json(list(client)).get("items");

        ResponseEntity<String> res = weather(client, "");

        assertThat(String.valueOf(res.getHeaders().getContentType())).startsWith("application/json");
        assertThat(res.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        JsonNode items = okItems(res);
        assertThat(items).hasSize(3);
        assertThat(ids(items)).containsExactly(london.get("id").asText(), paris.get("id").asText(),
                tokyo.get("id").asText());
        verify(provider, times(3)).currentAtLocation(any(), any());

        assertThat(fieldNames(items.get(0))).containsExactlyInAnyOrder("id", "city", "label", "location", "weather");
        assertThat(items.get(0).get("label").asText()).isEqualTo("Home");
        assertThat(fieldNames(items.get(1))).containsExactlyInAnyOrder("id", "city", "location", "weather");
        assertThat(fieldNames(items.get(2))).containsExactlyInAnyOrder("id", "city", "location", "weather");

        assertWeatherItem(items.get(0), listed.get(0), w("London", "metric"));
        assertWeatherItem(items.get(1), listed.get(1), w("Paris", "metric"));
        assertWeatherItem(items.get(2), listed.get(2), w("Tokyo", "metric"));
        items.forEach(i -> {
            assertThat(i.at("/weather/observedAt").asText()).isEqualTo("2026-11-15T10:00:00Z");
            assertThat(i.at("/weather/units").asText()).isEqualTo("metric");
        });

        assertThat(rows(client)).as("AC-23: no row changed").isEqualTo(before);
    }

    @Test
    @DisplayName("AC-1 (edge): a moved service clock is reflected once for the whole response (observedAt equal for every item)")
    void ac1_edge_observedAtIsTheRequestInstant() {
        CLOCK.set(Instant.parse("2026-11-15T10:05:07.900Z"));

        JsonNode items = okItems(weather(client, ""));

        items.forEach(i -> assertThat(i.at("/weather/observedAt").asText()).isEqualTo("2026-11-15T10:05:07Z"));
    }

    // ---------------------------------------------------------------- AC-2

    @Test
    @DisplayName("AC-2: after a reorder to T, L, P the items are in order T, L, P")
    void ac2_storedOrder() {
        String l = london.get("id").asText();
        String p = paris.get("id").asText();
        String t = tokyo.get("id").asText();
        assertThat(reorder(client, reorderBody(t, l, p)).getStatusCode().value()).isEqualTo(200);

        assertThat(ids(okItems(weather(client, "")))).containsExactly(t, l, p);
    }

    // ---------------------------------------------------------------- AC-3

    @Test
    @DisplayName("AC-3: units=imperial gives W(city, imperial); units=metric and no units give W(city, metric); unknown parameters are ignored")
    void ac3_units() {
        JsonNode imperial = okItems(weather(client, "?units=imperial"));
        assertThat(imperial.get(0).get("weather")).isEqualTo(w("London", "imperial"));
        assertThat(imperial.get(1).get("weather")).isEqualTo(w("Paris", "imperial"));
        assertThat(imperial.get(2).get("weather")).isEqualTo(w("Tokyo", "imperial"));
        imperial.forEach(i -> assertThat(i.at("/weather/units").asText()).isEqualTo("imperial"));

        JsonNode metric = okItems(weather(client, "?units=metric"));
        JsonNode none = okItems(weather(client, ""));
        assertThat(metric).isEqualTo(none);
        assertThat(metric.get(0).get("weather")).isEqualTo(w("London", "metric"));
        assertThat(metric.get(1).get("weather")).isEqualTo(w("Paris", "metric"));
        assertThat(metric.get(0).get("weather")).isNotEqualTo(imperial.get(0).get("weather"));

        assertThat(okItems(weather(client, "?page=2&foo=bar"))).isEqualTo(none);
    }

    @ParameterizedTest(name = "units={0}")
    @ValueSource(strings = {"IMPERIAL", "kelvin", ""})
    @DisplayName("AC-3: invalid units give the same status and errors as /weather/current (400 VALIDATION_FAILED, units: must be one of: metric, imperial); provider not called")
    void ac3_invalidUnits(String units) {
        ResponseEntity<String> res = weather(client, "?units=" + units);
        verifyNoInteractions(provider);

        JsonNode problem = assertProblem(res, 400, "VALIDATION_FAILED");
        JsonNode reference = json(get("/api/v1/weather/current?city=London&units=" + units));
        assertThat(problem.get("status")).isEqualTo(reference.get("status"));
        assertThat(problem.get("errors")).isEqualTo(reference.get("errors"));
        assertThat(errors(problem)).containsExactly("units: must be one of: metric, imperial");
    }

    // ---------------------------------------------------------------- AC-4

    @Test
    @DisplayName("AC-4: a client with no favourites gets 200 {\"items\": []} and the provider is not called")
    void ac4_emptyList() {
        ResponseEntity<String> res = weather(newClientId(), "");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(json(res)).isEqualTo(JSON.createObjectNode().set("items", JSON.createArrayNode()));
        verifyNoInteractions(provider);
    }

    // ---------------------------------------------------------------- AC-5

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
    @DisplayName("AC-5: a missing, empty, 65-char or ill-formed X-Client-Id is 400 VALIDATION_FAILED with the X-Client-Id error; provider not called")
    void ac5_invalidClientId(String label, String header) {
        JsonNode problem = assertProblem(weather(header, ""), 400, "VALIDATION_FAILED");

        assertThat(errors(problem)).containsExactly(CLIENT_ID_ERROR);
        verifyNoInteractions(provider);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidClientIds")
    @DisplayName("AC-5: an invalid X-Client-Id together with units=kelvin still reports the X-Client-Id error; provider not called")
    void ac5_invalidClientIdAndUnits(String label, String header) {
        JsonNode problem = assertProblem(weather(header, "?units=kelvin"), 400, "VALIDATION_FAILED");

        assertThat(errors(problem)).contains(CLIENT_ID_ERROR);
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("AC-5: a 64-character valid X-Client-Id is accepted")
    void ac5_sixtyFourChars() {
        String id64 = track("it-faw-64-" + "x".repeat(54));
        assertThat(id64).hasSize(64);
        create(id64, "Sydney");

        JsonNode items = okItems(weather(id64, ""));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).at("/weather/location/name").asText()).isEqualTo("Sydney");
    }

    // ---------------------------------------------------------------- AC-6

    @Test
    @DisplayName("AC-6: client B sees only its own favourite (Tokyo); a client without favourites gets [] although A has some")
    void ac6_isolation() {
        String a = newClientId();
        create(a, "London");
        create(a, "Paris");
        String b = newClientId();
        JsonNode bTokyo = create(b, "Tokyo");

        JsonNode items = okItems(weather(b, ""));
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("id")).isEqualTo(bTokyo.get("id"));
        assertThat(items.get(0).get("city").asText()).isEqualTo("Tokyo");

        assertThat(okItems(weather(newClientId(), ""))).isEmpty();
        assertThat(okItems(weather(a, ""))).hasSize(2);
    }

    // ---------------------------------------------------------------- AC-11, AC-12, AC-23

    @Test
    @DisplayName("AC-11/AC-23 (stub path): Paris's lookup throws WeatherProviderUnavailableException: 200, 3 items in order, Paris has only the UPSTREAM_UNAVAILABLE error, the others W(city, metric); no row changed")
    void ac11_oneItemFails() {
        List<Map<String, Object>> before = rows(client);
        doThrow(new WeatherProviderUnavailableException("Open-Meteo forecast call failed: status=500", null))
                .when(provider).currentAtLocation(isParis(), any());

        JsonNode items = okItems(weather(client, ""));

        assertThat(items).hasSize(3);
        assertWeatherItem(items.get(0), london, w("London", "metric"));
        assertErrorItem(items.get(1), paris, "UPSTREAM_UNAVAILABLE", UPSTREAM_DETAIL);
        assertThat(fieldNames(items.get(1))).containsExactlyInAnyOrder("id", "city", "location", "error");
        assertWeatherItem(items.get(2), tokyo, w("Tokyo", "metric"));
        assertThat(rows(client)).as("AC-23: no row changed").isEqualTo(before);
    }

    @Test
    @DisplayName("AC-11 (edge): a failing labelled item keeps its label next to the error")
    void ac11_edge_failedItemKeepsLabel() {
        doThrow(new WeatherProviderUnavailableException("x", null))
                .when(provider).currentAtLocation(argThat(l -> l != null && l.name().equals("London")), any());

        JsonNode items = okItems(weather(client, ""));

        assertErrorItem(items.get(0), london, "UPSTREAM_UNAVAILABLE", UPSTREAM_DETAIL);
        assertThat(items.get(0).get("label").asText()).isEqualTo("Home");
    }

    @Test
    @DisplayName("AC-12/AC-23 (stub path): every lookup fails: still 200 with 3 UPSTREAM_UNAVAILABLE items and no weather; after removing the failure the same request gives 3 weather items")
    void ac12_allItemsFail() {
        List<Map<String, Object>> before = rows(client);
        doThrow(new WeatherProviderUnavailableException("x", null)).when(provider).currentAtLocation(any(), any());

        JsonNode failed = okItems(weather(client, ""));

        assertThat(failed).hasSize(3);
        assertErrorItem(failed.get(0), london, "UPSTREAM_UNAVAILABLE", UPSTREAM_DETAIL);
        assertErrorItem(failed.get(1), paris, "UPSTREAM_UNAVAILABLE", UPSTREAM_DETAIL);
        assertErrorItem(failed.get(2), tokyo, "UPSTREAM_UNAVAILABLE", UPSTREAM_DETAIL);

        doCallRealMethod().when(provider).currentAtLocation(any(), any());
        JsonNode recovered = okItems(weather(client, ""));

        assertThat(recovered).hasSize(3).allSatisfy(i -> {
            assertThat(i.has("weather")).isTrue();
            assertThat(i.has("error")).isFalse();
        });
        assertThat(rows(client)).as("AC-23: no row changed").isEqualTo(before);
    }

    // ---------------------------------------------------------------- AC-14

    @Test
    @DisplayName("AC-14: Paris's lookup throws LocationNotFoundException: that item is LOCATION_NOT_FOUND with a fixed detail without the city; the others are unaffected; 200")
    void ac14_notFoundFromProvider() {
        doThrow(new LocationNotFoundException("Paris")).when(provider).currentAtLocation(isParis(), any());

        JsonNode items = okItems(weather(client, ""));

        assertWeatherItem(items.get(0), london, w("London", "metric"));
        assertErrorItem(items.get(1), paris, "LOCATION_NOT_FOUND", NOT_FOUND_DETAIL);
        assertThat(items.get(1).at("/error/detail").asText()).doesNotContain("Paris");
        assertWeatherItem(items.get(2), tokyo, w("Tokyo", "metric"));
    }

    @Test
    @DisplayName("AC-14: a row inserted directly at 0.0/0.0 (unknown to the stub) is LOCATION_NOT_FOUND; the API-created item still has weather")
    void ac14_notFoundForUnknownStoredLocation() {
        String c = newClientId();
        JsonNode sydney = create(c, "Sydney");
        UUID atlantis = FavouriteRows.insert(jdbc, c, "Atlantis", "Atlantis", "XX", 0.0, 0.0, 2, T0);

        JsonNode items = okItems(weather(c, ""));

        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("id")).isEqualTo(sydney.get("id"));
        assertThat(items.get(0).get("weather")).isEqualTo(w("Sydney", "metric"));
        assertThat(items.get(1).get("id").asText()).isEqualTo(atlantis.toString());
        assertThat(items.get(1).get("error"))
                .isEqualTo(JSON.createObjectNode().put("code", "LOCATION_NOT_FOUND").put("detail", NOT_FOUND_DETAIL));
        assertThat(items.get(1).has("weather")).isFalse();
        assertThat(items.get(1).toString()).doesNotContain("\"detail\":\"Atlantis");
    }

    // ---------------------------------------------------------------- AC-15

    @Test
    @DisplayName("AC-15: an IllegalStateException in one lookup is 500 INTERNAL_ERROR (Problem Details, no message or stack trace in the body), not a per-item error")
    void ac15_unexpectedFailureIs500() {
        doThrow(new IllegalStateException("boom-SECRET15")).when(provider).currentAtLocation(isParis(), any());

        ResponseEntity<String> res = weather(client, "");

        JsonNode problem = assertProblem(res, 500, "INTERNAL_ERROR");
        assertThat(res.getBody()).doesNotContain("boom-SECRET15").doesNotContain("IllegalStateException")
                .doesNotContain("at com.example").doesNotContain("items");
        assertThat(problem.get("requestId").asText()).isEqualTo(res.getHeaders().getFirst("X-Request-Id"));
    }

    // ---------------------------------------------------------------- AC-20

    @Test
    @DisplayName("AC-20: GET /favourites/weather is the new endpoint (never 400 field id); POST is 405 with Allow containing GET; GET /favourites/<uuid> is still 404")
    void ac20_routing() {
        ResponseEntity<String> ok = weather(newClientId(), "");
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody()).doesNotContain("\"field\":\"id\"");

        ResponseEntity<String> postRes = send(HttpMethod.POST, WEATHER, client, null, Map.of());
        assertProblem(postRes, 405, "METHOD_NOT_ALLOWED");
        assertThat(postRes.getHeaders().getAllow()).contains(HttpMethod.GET);

        assertProblem(getOne(client, UUID.randomUUID()), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("AC-20 (edge, documented A-1): PATCH and DELETE /favourites/weather hit {id} and are 400 on field id; PUT is 405")
    void ac20_edge_otherMethods() {
        JsonNode patchProblem = assertProblem(send(HttpMethod.PATCH, WEATHER, client, "{\"label\":\"x\"}", Map.of()),
                400, "VALIDATION_FAILED");
        assertThat(patchProblem.path("errors").get(0).get("field").asText()).isEqualTo("id");
        JsonNode deleteProblem = assertProblem(send(HttpMethod.DELETE, WEATHER, client, null, Map.of()),
                400, "VALIDATION_FAILED");
        assertThat(deleteProblem.path("errors").get(0).get("field").asText()).isEqualTo("id");
        ResponseEntity<String> put = send(HttpMethod.PUT, WEATHER, client, null, Map.of());
        assertProblem(put, 405, "METHOD_NOT_ALLOWED");
        assertThat(put.getHeaders().getAllow()).contains(HttpMethod.GET);
        assertThat(rowCount(client)).isEqualTo(3);
    }

    // ---------------------------------------------------------------- AC-23 (connections)

    @Test
    @DisplayName("AC-23: no transaction is active and no pool connection is checked out while the provider is called")
    void ac23_noConnectionHeldDuringProviderCalls() throws SQLException {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        List<String> observed = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            observed.add("tx=" + TransactionSynchronizationManager.isActualTransactionActive()
                    + " active=" + pool.getHikariPoolMXBean().getActiveConnections());
            return inv.callRealMethod();
        }).when(provider).currentAtLocation(any(), any());

        okItems(weather(client, ""));

        verify(provider, atLeastOnce()).currentAtLocation(any(), any());
        assertThat(observed).hasSize(3).allMatch("tx=false active=0"::equals);
    }
}
