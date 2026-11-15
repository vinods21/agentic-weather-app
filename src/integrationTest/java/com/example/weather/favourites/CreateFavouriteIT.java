package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.weather.provider.WeatherProviderUnavailableException;
import com.example.weather.support.FavouritesIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** Create: AC-2, AC-3, AC-4, AC-6, AC-7, AC-8. */
@ExtendWith(OutputCaptureExtension.class)
class CreateFavouriteIT extends FavouritesIntegrationTestBase {

    @Test
    @DisplayName("AC-2: create is 201 with Location, X-Request-Id, the stripped city, the provider location and clock timestamps; one row")
    void ac2_createReturnsResourceAndStoresRow() {
        String client = newClientId();

        ResponseEntity<String> res = post(client, "{\"city\": \"  london  \", \"label\": \"Home\"}");

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat(res.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        JsonNode body = json(res);
        String id = body.get("id").asText();
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        assertThat(res.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(BASE + "/" + id);
        assertThat(body.get("city").asText()).isEqualTo("london");
        assertThat(body.get("label").asText()).isEqualTo("Home");
        JsonNode location = body.get("location");
        assertThat(location.get("name").asText()).isEqualTo("London");
        assertThat(location.get("country").asText()).isEqualTo("GB");
        assertThat(location.get("latitude").asDouble()).isEqualTo(51.5074);
        assertThat(location.get("longitude").asDouble()).isEqualTo(-0.1278);
        assertThat(body.get("createdAt").asText()).isEqualTo("2026-11-15T10:00:00Z");
        assertThat(body.get("updatedAt").asText()).isEqualTo("2026-11-15T10:00:00Z");
        assertThat(body.size()).as("no other properties").isEqualTo(6);

        List<Map<String, Object>> rows = rows(client);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("id").toString()).isEqualTo(id);
        assertThat(row.get("city")).isEqualTo("london");
        assertThat(row.get("city_key")).isEqualTo("london");
        assertThat(row.get("label")).isEqualTo("Home");
        assertThat(row.get("location_name")).isEqualTo("London");
        assertThat(row.get("location_country")).isEqualTo("GB");
        assertThat(row.get("location_latitude")).isEqualTo(51.5074);
        assertThat(row.get("location_longitude")).isEqualTo(-0.1278);
        assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(T0);
        assertThat(((Timestamp) row.get("updated_at")).toInstant()).isEqualTo(T0);
        verify(provider, times(1)).locate("london");
    }

    @Test
    @DisplayName("AC-2 (edge): timestamps are truncated to whole seconds")
    void ac2_edge_timestampsTruncatedToSeconds() {
        CLOCK.set(T0.plusMillis(987));
        String client = newClientId();

        JsonNode body = json(post(client, "{\"city\": \"Paris\"}"));

        assertThat(body.get("createdAt").asText()).isEqualTo("2026-11-15T10:00:00Z");
        assertThat(((Timestamp) rows(client).get(0).get("created_at")).toInstant()).isEqualTo(T0);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"{\"city\": \"Paris\"}", "{\"city\": \"Paris\", \"label\": null}"})
    @DisplayName("AC-3: without a label (missing or null) the 201 body has no label property and the row's label is NULL")
    void ac3_noLabel(String body) {
        String client = newClientId();

        ResponseEntity<String> res = post(client, body);

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat(json(res).has("label")).isFalse();
        assertThat(res.getBody()).doesNotContain("\"label\"");
        assertThat(rows(client)).singleElement().satisfies(r -> assertThat(r.get("label")).isNull());
    }

    static Stream<Arguments> invalidCreateBodies() {
        return Stream.of(
                Arguments.of("{}", List.of("city: must not be blank")),
                Arguments.of("{\"city\": \"   \"}", List.of("city: must not be blank")),
                Arguments.of("{\"city\": \"" + "a".repeat(101) + "\"}", List.of("city: must be at most 100 characters")),
                Arguments.of("{\"city\": \"London\", \"label\": \"   \"}", List.of("label: must not be blank")),
                Arguments.of("{\"city\": \"London\", \"label\": \"" + "b".repeat(51) + "\"}",
                        List.of("label: must be at most 50 characters")),
                Arguments.of("{\"city\": \"\", \"label\": \"\"}",
                        List.of("city: must not be blank", "label: must not be blank")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCreateBodies")
    @DisplayName("AC-4: an invalid create body is 400 VALIDATION_FAILED with exactly the listed errors; no row, no provider call")
    void ac4_validation(String body, List<String> expected) {
        String client = newClientId();

        JsonNode problem = assertProblem(post(client, body), 400, "VALIDATION_FAILED");

        assertThat(errors(problem)).containsExactlyElementsOf(expected);
        assertThat(rowCount(client)).isZero();
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("AC-4: a 100-character city (after stripping) passes validation and reaches the provider (stub: 404 LOCATION_NOT_FOUND)")
    void ac4_hundredCharCityReachesProvider() {
        String client = newClientId();
        String city = "c".repeat(100);

        assertProblem(post(client, "{\"city\": \"   " + city + "   \"}"), 404, "LOCATION_NOT_FOUND");

        verify(provider).locate(city);
        assertThat(rowCount(client)).isZero();
    }

    @Test
    @DisplayName("AC-4: a 50-character label (after stripping) gives 201")
    void ac4_fiftyCharLabelAccepted() {
        String client = newClientId();
        String label = "L".repeat(50);

        ResponseEntity<String> res = post(client, "{\"city\": \"London\", \"label\": \"  " + label + "  \"}");

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat(json(res).get("label").asText()).isEqualTo(label);
        assertThat(rows(client).get(0).get("label")).isEqualTo(label);
    }

    @Test
    @DisplayName("AC-6: a text/plain body is 415 and writes no row")
    void ac6_unsupportedMediaType() {
        String client = newClientId();

        ResponseEntity<String> res = send(HttpMethod.POST, BASE, client, "London",
                Map.of(HttpHeaders.CONTENT_TYPE, "text/plain"));

        assertThat(res.getStatusCode().value()).isEqualTo(415);
        assertThat(rowCount(client)).isZero();
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("AC-7: an unknown city is 404 LOCATION_NOT_FOUND naming the city; no row")
    void ac7_unknownCity() {
        String client = newClientId();

        JsonNode problem = assertProblem(post(client, "{\"city\": \"Atlantis\"}"), 404, "LOCATION_NOT_FOUND");

        assertThat(problem.get("detail").asText()).contains("Atlantis");
        assertThat(rowCount(client)).isZero();
    }

    @Test
    @DisplayName("AC-8: provider unavailable is 503 UPSTREAM_UNAVAILABLE with the request id, no row, WARN logged; after reset 201")
    void ac8_upstreamUnavailable(CapturedOutput output) {
        WeatherProviderUnavailableException down = new WeatherProviderUnavailableException("simulated outage", null);
        doThrow(down).when(provider).locate(anyString());
        doThrow(down).when(provider).current(anyString());
        doThrow(down).when(provider).currentAt(anyString(), any());
        doThrow(down).when(provider).forecast(anyString(), any(), anyInt());
        String client = newClientId();

        ResponseEntity<String> res = post(client, "{\"city\": \"London\"}", Map.of("X-Request-Id", "it-fav-8"));

        JsonNode problem = assertProblem(res, 503, "UPSTREAM_UNAVAILABLE");
        assertThat(problem.get("requestId").asText()).isEqualTo("it-fav-8");
        assertThat(res.getHeaders().getFirst("X-Request-Id")).isEqualTo("it-fav-8");
        assertThat(res.getBody()).doesNotContain("simulated outage");
        assertThat(rowCount(client)).isZero();
        String warn = output.getAll().lines().filter(l -> l.contains("WARN") && l.contains("it-fav-8"))
                .collect(Collectors.joining("\n"));
        assertThat(warn).isNotBlank();

        reset(provider);
        assertThat(post(client, "{\"city\": \"London\"}", Map.of("X-Request-Id", "it-fav-8")).getStatusCode().value())
                .isEqualTo(201);
        assertThat(rowCount(client)).isEqualTo(1);
    }
}
