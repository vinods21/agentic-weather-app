package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.example.weather.provider.WeatherProviderUnavailableException;
import com.example.weather.support.FavouritesIntegrationTestBase;
import com.example.weather.support.OpenApiSchemaAssert;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * AC-19: real GET /api/v1/favourites/weather bodies conform to the schemas the spec declares for
 * the operation and status (AC-1, AC-4, AC-11 mixed, AC-12 all-error, and the AC-3/AC-5 400s).
 */
class FavouritesWeatherContractConformanceIT extends FavouritesIntegrationTestBase {

    private static final String WEATHER = BASE + "/weather";

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

    private ResponseEntity<String> weather(String clientId, String query) {
        return send(HttpMethod.GET, WEATHER + query, clientId, null, Map.of());
    }

    private JsonNode declared(String status, String mediaType) {
        JsonNode response = schemas.resolve(spec.path("paths").path(WEATHER).path("get").path("responses").path(status));
        JsonNode schema = response.path("content").path(mediaType).path("schema");
        assertThat(schema.isMissingNode()).as("GET %s %s %s declared", WEATHER, status, mediaType).isFalse();
        return schema;
    }

    private JsonNode conforms(ResponseEntity<String> res, int status) {
        assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(status);
        String mediaType = status < 400 ? "application/json" : "application/problem+json";
        JsonNode body = json(res);
        schemas.assertConforms(body, declared(String.valueOf(status), mediaType));
        if (status == 200) {
            body.get("items").forEach(i -> assertThat(i.has("weather") ^ i.has("error"))
                    .as("exactly one of weather/error in %s", i).isTrue());
        }
        return body;
    }

    @Test
    @DisplayName("AC-19: the AC-1 body (all weather) and the AC-4 empty body conform to FavouriteWeatherList")
    void ac19_successBodiesConform() {
        assertThat(conforms(weather(client, ""), 200).get("items")).hasSize(3);
        assertThat(conforms(weather(client, "?units=imperial"), 200).get("items")).hasSize(3);
        assertThat(conforms(weather(newClientId(), ""), 200).get("items")).isEmpty();
    }

    @Test
    @DisplayName("AC-19: the AC-11 mixed body conforms to FavouriteWeatherList")
    void ac19_mixedBodyConforms() {
        doThrow(new WeatherProviderUnavailableException("x", null))
                .when(provider).currentAtLocation(argThat(l -> l != null && l.latitude() == 48.8566), any());

        JsonNode items = conforms(weather(client, ""), 200).get("items");

        assertThat(items.get(1).has("error")).isTrue();
        assertThat(items.get(0).has("weather")).isTrue();
    }

    @Test
    @DisplayName("AC-19: the AC-12 all-error body conforms to FavouriteWeatherList")
    void ac19_allErrorBodyConforms() {
        doThrow(new WeatherProviderUnavailableException("x", null)).when(provider).currentAtLocation(any(), any());

        JsonNode items = conforms(weather(client, ""), 200).get("items");

        assertThat(items).hasSize(3).allSatisfy(i -> assertThat(i.has("error")).isTrue());
    }

    @Test
    @DisplayName("AC-19: the AC-3 (units) and AC-5 (X-Client-Id) 400 bodies conform to Problem")
    void ac19_badRequestBodiesConform() {
        conforms(weather(client, "?units=kelvin"), 400);
        conforms(weather(client, "?units="), 400);
        conforms(weather(null, ""), 400);
        conforms(weather("a b", "?units=kelvin"), 400);
    }

    @Test
    @DisplayName("AC-19 (edge): the 500 body (unexpected failure) conforms to Problem as declared")
    void ac19_edge_internalErrorConforms() {
        doThrow(new IllegalStateException("x")).when(provider).currentAtLocation(any(), any());

        conforms(weather(client, ""), 500);
    }
}
