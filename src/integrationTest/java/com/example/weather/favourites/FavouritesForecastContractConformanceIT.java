package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.example.weather.provider.LocationNotFoundException;
import com.example.weather.provider.WeatherProviderUnavailableException;
import com.example.weather.support.FavouritesIntegrationTestBase;
import com.example.weather.support.OpenApiSchemaAssert;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * AC-22: real GET /api/v1/favourites/forecast bodies conform to the schemas the spec declares for the
 * operation and status (AC-1, days=7, AC-6 empty, AC-14 mixed, AC-15 all-error, the AC-5/AC-7 400s and
 * the AC-18 500). Shares the {@link FavouritesIntegrationTestBase} context.
 */
class FavouritesForecastContractConformanceIT extends FavouritesIntegrationTestBase {

    private static final String FORECAST = BASE + "/forecast";

    private JsonNode spec;
    private OpenApiSchemaAssert schemas;
    private String client;

    @BeforeEach
    void loadSpecAndSeed() {
        spec = json(get("/api/v1/openapi.json"));
        schemas = new OpenApiSchemaAssert(spec);
        client = newClientId();
        post(client, "{\"city\": \"London\", \"label\": \"Home\"}");
        create(client, "Paris");
        create(client, "Tokyo");
    }

    private ResponseEntity<String> forecast(String clientId, String query) {
        return send(HttpMethod.GET, FORECAST + query, clientId, null, Map.of());
    }

    private JsonNode declared(String status, String mediaType) {
        JsonNode response = schemas.resolve(spec.path("paths").path(FORECAST).path("get").path("responses").path(status));
        JsonNode schema = response.path("content").path(mediaType).path("schema");
        assertThat(schema.isMissingNode()).as("GET %s %s %s declared", FORECAST, status, mediaType).isFalse();
        return schema;
    }

    private JsonNode conforms(ResponseEntity<String> res, int status) {
        assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(status);
        String mediaType = status < 400 ? "application/json" : "application/problem+json";
        JsonNode body = json(res);
        schemas.assertConforms(body, declared(String.valueOf(status), mediaType));
        if (status == 200) {
            body.get("items").forEach(i -> assertThat(i.has("forecast") ^ i.has("error"))
                    .as("exactly one of forecast/error in %s", i).isTrue());
        }
        return body;
    }

    @Test
    @DisplayName("AC-22: the AC-1 body (default days), a days=7 body, an imperial body and the AC-6 empty body conform to FavouriteForecastList")
    void ac22_successBodiesConform() {
        assertThat(conforms(forecast(client, ""), 200).get("items")).hasSize(3);
        JsonNode seven = conforms(forecast(client, "?days=7"), 200).get("items");
        assertThat(seven).hasSize(3).allSatisfy(i -> assertThat(i.at("/forecast/days")).hasSize(7));
        assertThat(conforms(forecast(client, "?units=imperial&days=1"), 200).get("items")).hasSize(3);
        assertThat(conforms(forecast(newClientId(), ""), 200).get("items")).isEmpty();
    }

    @Test
    @DisplayName("AC-22: the AC-14 mixed body (plus a LOCATION_NOT_FOUND item) conforms to FavouriteForecastList")
    void ac22_mixedBodyConforms() {
        doThrow(new WeatherProviderUnavailableException("x", null))
                .when(provider).forecastAtLocation(argThat(l -> l != null && l.latitude() == 48.8566), any(), anyInt());
        doThrow(new LocationNotFoundException("x"))
                .when(provider).forecastAtLocation(argThat(l -> l != null && l.name().equals("Tokyo")), any(), anyInt());

        JsonNode items = conforms(forecast(client, ""), 200).get("items");

        assertThat(items.get(0).has("forecast")).isTrue();
        assertThat(items.get(1).at("/error/code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(items.get(2).at("/error/code").asText()).isEqualTo("LOCATION_NOT_FOUND");
    }

    @Test
    @DisplayName("AC-22: the AC-15 all-error body conforms to FavouriteForecastList")
    void ac22_allErrorBodyConforms() {
        doThrow(new WeatherProviderUnavailableException("x", null))
                .when(provider).forecastAtLocation(any(), any(), anyInt());

        JsonNode items = conforms(forecast(client, ""), 200).get("items");

        assertThat(items).hasSize(3).allSatisfy(i -> assertThat(i.has("error")).isTrue());
    }

    @Test
    @DisplayName("AC-22: the AC-5 (units/days) and AC-7 (X-Client-Id) 400 bodies conform to Problem")
    void ac22_badRequestBodiesConform() {
        List<String> queries = new ArrayList<>(List.of("?units=kelvin", "?units=", "?days=0", "?days=abc",
                "?units=kelvin&days=99"));
        for (String q : queries) {
            conforms(forecast(client, q), 400);
        }
        conforms(forecast(null, ""), 400);
        conforms(forecast("a b", "?units=kelvin&days=0"), 400);
        conforms(forecast("a".repeat(65), ""), 400);
    }

    @Test
    @DisplayName("AC-22: the AC-18 500 body (unexpected failure) conforms to Problem as declared")
    void ac22_internalErrorConforms() {
        doThrow(new IllegalStateException("x")).when(provider).forecastAtLocation(any(), any(), anyInt());

        conforms(forecast(client, ""), 500);
    }

    @Test
    @DisplayName("AC-22 (edge): the spec declares no 404 or 503 for GET /favourites/forecast")
    void ac22_edge_no404or503Declared() {
        JsonNode responses = spec.path("paths").path(FORECAST).path("get").path("responses");
        List<String> codes = new ArrayList<>();
        responses.fieldNames().forEachRemaining(codes::add);
        assertThat(codes).containsExactlyInAnyOrder("200", "400", "500");
    }
}
