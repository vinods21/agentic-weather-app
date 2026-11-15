package com.example.weather.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.ApiIntegrationTestBase;
import com.example.weather.support.OpenApiSchemaAssert;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The served OpenAPI document (AC-19, AC-20). */
class OpenApiIT extends ApiIntegrationTestBase {

    static final List<String> ERROR_CODES = List.of("VALIDATION_FAILED", "LOCATION_NOT_FOUND", "NOT_FOUND",
            "METHOD_NOT_ALLOWED", "UPSTREAM_UNAVAILABLE", "INTERNAL_ERROR");

    private ResponseEntity<String> response;
    private JsonNode spec;
    private OpenApiSchemaAssert schemas;

    @BeforeEach
    void load() {
        response = get("/api/v1/openapi.json");
        spec = json(response);
        schemas = new OpenApiSchemaAssert(spec);
    }

    @Test
    @DisplayName("AC-19: openapi.json is served as JSON with openapi 3.x, version 1.0.0 and both GET paths")
    void ac19_documentIsServed() {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(spec.get("openapi").asText()).startsWith("3.");
        assertThat(spec.at("/info/version").asText()).isEqualTo("1.0.0");
        assertThat(spec.at("/paths/~1api~1v1~1weather~1current/get").isObject()).isTrue();
        assertThat(spec.at("/paths/~1api~1v1~1weather~1forecast/get").isObject()).isTrue();
    }

    @Test
    @DisplayName("AC-20: info.description states the compatibility policy (clients ignore unknown fields)")
    void ac20_compatibilityPolicyDocumented() {
        assertThat(spec.at("/info/description").asText()).containsIgnoringCase("ignore unknown");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/v1/weather/current", "/api/v1/weather/forecast"})
    @DisplayName("AC-20: city and units parameters are documented with required flag, limits, enum and default")
    void ac20_cityAndUnitsParameters(String path) {
        Map<String, JsonNode> params = parameters(path);

        JsonNode city = params.get("city");
        assertThat(city).as("city parameter").isNotNull();
        assertThat(city.get("in").asText()).isEqualTo("query");
        assertThat(city.get("required").asBoolean()).isTrue();
        assertThat(city.at("/schema/type").asText()).isEqualTo("string");
        assertThat(city.at("/schema/minLength").asInt()).isEqualTo(1);
        assertThat(city.at("/schema/maxLength").asInt()).isEqualTo(100);

        JsonNode units = params.get("units");
        assertThat(units).as("units parameter").isNotNull();
        assertThat(units.path("required").asBoolean(false)).isFalse();
        assertThat(texts(units.at("/schema/enum"))).containsExactlyInAnyOrder("metric", "imperial");
        assertThat(units.at("/schema/default").asText()).isEqualTo("metric");
    }

    @Test
    @DisplayName("AC-20: forecast documents days as an optional integer 1..7 with default 5; current has no days")
    void ac20_daysParameter() {
        JsonNode days = parameters("/api/v1/weather/forecast").get("days");
        assertThat(days).as("days parameter").isNotNull();
        assertThat(days.path("required").asBoolean(false)).isFalse();
        assertThat(days.at("/schema/type").asText()).isEqualTo("integer");
        assertThat(days.at("/schema/minimum").asInt()).isEqualTo(1);
        assertThat(days.at("/schema/maximum").asInt()).isEqualTo(7);
        assertThat(days.at("/schema/default").asInt()).isEqualTo(5);

        assertThat(parameters("/api/v1/weather/current")).doesNotContainKey("days");
    }

    @Test
    @DisplayName("AC-20: 200 schemas list every contract field as required (incl. nested objects)")
    void ac20_successSchemasRequireEveryField() {
        JsonNode current = successSchema("/api/v1/weather/current");
        assertThat(texts(current.get("required"))).containsExactlyInAnyOrderElementsOf(CurrentWeatherIT.CURRENT_FIELDS);

        JsonNode forecast = successSchema("/api/v1/weather/forecast");
        assertThat(texts(forecast.get("required"))).containsExactlyInAnyOrder("location", "units", "days");

        JsonNode location = schemas.resolve(current.at("/properties/location"));
        assertThat(texts(location.get("required")))
                .containsExactlyInAnyOrder("name", "country", "latitude", "longitude");
        JsonNode condition = schemas.resolve(current.at("/properties/condition"));
        assertThat(texts(condition.get("required"))).containsExactlyInAnyOrder("code", "description");
        assertThat(texts(condition.at("/properties/code/enum")))
                .containsExactlyInAnyOrderElementsOf(CurrentWeatherIT.CONDITION_CODES);
        JsonNode day = schemas.resolve(forecast.at("/properties/days/items"));
        assertThat(texts(day.get("required"))).containsExactlyInAnyOrder(
                "date", "minTemperature", "maxTemperature", "precipitationProbability", "condition");
        assertThat(schemas.resolve(forecast.at("/properties/location"))).isEqualTo(location);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/v1/weather/current", "/api/v1/weather/forecast"})
    @DisplayName("AC-20: 400/404/500/503 use application/problem+json and reference the one shared Problem schema")
    void ac20_errorResponsesUseSharedProblem(String path) {
        JsonNode responses = operation(path).get("responses");
        for (String status : List.of("400", "404", "500", "503")) {
            JsonNode r = schemas.resolve(responses.get(status));
            assertThat(r).as("response %s", status).isNotNull();
            JsonNode schema = r.at("/content/application~1problem+json/schema");
            assertThat(schema.isMissingNode()).as("%s has application/problem+json", status).isFalse();
            assertThat(schema.path("$ref").asText()).as("%s schema ref", status)
                    .isEqualTo("#/components/schemas/Problem");
        }
    }

    @Test
    @DisplayName("AC-20: Problem.code enum is exactly the six error codes")
    void ac20_problemCodeEnum() {
        JsonNode problem = schemas.schema("Problem");
        assertThat(texts(problem.at("/properties/code/enum"))).containsExactlyInAnyOrderElementsOf(ERROR_CODES);
        assertThat(texts(problem.get("required")))
                .contains("type", "title", "status", "detail", "instance", "code", "requestId")
                .doesNotContain("errors");
    }

    private JsonNode operation(String path) {
        JsonNode op = spec.path("paths").path(path).path("get");
        assertThat(op.isObject()).as("GET %s documented", path).isTrue();
        return op;
    }

    private Map<String, JsonNode> parameters(String path) {
        Map<String, JsonNode> byName = new HashMap<>();
        for (JsonNode p : operation(path).path("parameters")) {
            JsonNode resolved = schemas.resolve(p);
            byName.put(resolved.get("name").asText(), resolved);
        }
        return byName;
    }

    private JsonNode successSchema(String path) {
        JsonNode ok = schemas.resolve(operation(path).at("/responses/200"));
        return schemas.resolve(ok.at("/content/application~1json/schema"));
    }

    static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }
}
