package com.example.weather.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.weather.support.ApiIntegrationTestBase;
import com.example.weather.support.OpenApiSchemaAssert;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Real response bodies conform to the matching OpenAPI schemas (AC-22). */
class ContractConformanceIT extends ApiIntegrationTestBase {

    private OpenApiSchemaAssert schemas;
    private JsonNode spec;

    @BeforeEach
    void loadSpec() {
        spec = json(get("/api/v1/openapi.json"));
        schemas = new OpenApiSchemaAssert(spec);
    }

    /** Schema declared for {@code status} of GET {@code path}, resolved through components.responses. */
    private JsonNode declaredSchema(String path, String status, String mediaType) {
        JsonNode response = schemas.resolve(spec.path("paths").path(path).path("get").path("responses").path(status));
        JsonNode schema = response.path("content").path(mediaType).path("schema");
        assertThat(schema.isMissingNode()).as("%s %s %s declared", path, status, mediaType).isFalse();
        return schema;
    }

    @ParameterizedTest(name = "units={0}")
    @ValueSource(strings = {"metric", "imperial"})
    @DisplayName("AC-22: 200 current-weather body (AC-1) conforms to the CurrentWeather schema")
    void ac22_currentWeatherConforms(String units) {
        ResponseEntity<String> res = get("/api/v1/weather/current?city=London&units=" + units);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        schemas.assertConforms(json(res), declaredSchema("/api/v1/weather/current", "200", "application/json"));
    }

    @ParameterizedTest(name = "days={0}")
    @ValueSource(ints = {1, 3, 7})
    @DisplayName("AC-22: 200 forecast body (AC-5) conforms to the Forecast schema")
    void ac22_forecastConforms(int days) {
        ResponseEntity<String> res = get("/api/v1/weather/forecast?city=London&days=" + days);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        schemas.assertConforms(json(res), declaredSchema("/api/v1/weather/forecast", "200", "application/json"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/api/v1/weather/current",
            "/api/v1/weather/current?city=London&units=kelvin",
            "/api/v1/weather/forecast?city=London&days=abc",
            "/api/v1/weather/forecast?units=kelvin&days=99"})
    @DisplayName("AC-22: 400 VALIDATION_FAILED bodies (AC-7) conform to the Problem schema")
    void ac22_validationProblemConforms(String uri) {
        ResponseEntity<String> res = get(uri);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        schemas.assertConforms(json(res), declaredSchema(path, "400", "application/problem+json"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/v1/weather/current", "/api/v1/weather/forecast"})
    @DisplayName("AC-22: 404 LOCATION_NOT_FOUND bodies (AC-10) conform to the Problem schema")
    void ac22_locationNotFoundConforms(String path) {
        ResponseEntity<String> res = get(path + "?city=Atlantis");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        schemas.assertConforms(json(res), declaredSchema(path, "404", "application/problem+json"));
    }

    @Test
    @DisplayName("AC-22 (edge): 404 NOT_FOUND and 405 bodies also conform to the shared Problem schema")
    void ac22_edge_routingProblemsConform() {
        schemas.assertConforms(json(get("/api/v1/does-not-exist")), "Problem");
        schemas.assertConforms(json(exchange(org.springframework.http.HttpMethod.POST,
                "/api/v1/weather/current?city=London", java.util.Map.of())), "Problem");
    }

    @Test
    @DisplayName("AC-22 (self-test): the checker rejects undocumented and missing properties")
    void ac22_checkerDetectsViolations() {
        ObjectNode body = (ObjectNode) json(get("/api/v1/weather/current?city=London"));
        JsonNode schema = schemas.schema("CurrentWeather");

        ObjectNode extra = body.deepCopy();
        extra.put("undocumented", 1);
        assertThatThrownBy(() -> schemas.assertConforms(extra, schema)).hasMessageContaining("undocumented property");

        ObjectNode missing = body.deepCopy();
        missing.remove("humidity");
        assertThatThrownBy(() -> schemas.assertConforms(missing, schema)).hasMessageContaining("humidity");

        ObjectNode badEnum = body.deepCopy();
        ((ObjectNode) badEnum.get("condition")).put("code", "HAIL");
        assertThatThrownBy(() -> schemas.assertConforms(badEnum, schema)).hasMessageContaining("not in enum");
    }
}
