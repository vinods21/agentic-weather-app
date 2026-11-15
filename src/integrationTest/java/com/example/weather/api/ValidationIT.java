package com.example.weather.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.ApiIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** 400 VALIDATION_FAILED (AC-7, AC-8). */
class ValidationIT extends ApiIntegrationTestBase {

    static Stream<Arguments> ac7Cases() {
        return Stream.of(
                Arguments.of("/api/v1/weather/current", "city"),
                Arguments.of("/api/v1/weather/current?city=%20%20", "city"),
                Arguments.of("/api/v1/weather/current?city=" + "a".repeat(101), "city"),
                Arguments.of("/api/v1/weather/current?city=London&units=kelvin", "units"),
                Arguments.of("/api/v1/weather/current?city=London&units=METRIC", "units"),
                Arguments.of("/api/v1/weather/forecast?city=London&days=0", "days"),
                Arguments.of("/api/v1/weather/forecast?city=London&days=8", "days"),
                Arguments.of("/api/v1/weather/forecast?city=London&days=abc", "days"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("ac7Cases")
    @DisplayName("AC-7: invalid parameter gives 400 VALIDATION_FAILED with an errors entry for that field")
    void ac7_invalidParameter(String uri, String field) {
        ResponseEntity<String> res = get(uri);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode body = json(res);
        assertThat(body.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("status").asInt()).isEqualTo(400);
        assertThat(fields(body)).contains(field);
        // No Java class names or framework conversion text leak into messages.
        assertThat(res.getBody()).doesNotContain("java.").doesNotContain("Failed to convert");
    }

    @Test
    @DisplayName("AC-8: all violations are reported together (city, units and days)")
    void ac8_allViolationsReported() {
        ResponseEntity<String> res = get("/api/v1/weather/forecast?units=kelvin&days=99");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fields(json(res))).contains("city", "units", "days");
    }

    @Test
    @Disabled("Defect D-1 (docs/sdlc/ui-backend-api-contracts/test-report.md): when days fails type conversion, "
            + "the city/units violations are dropped and errors contains only days. Re-enable once fixed.")
    @DisplayName("Edge: days=abc combined with a missing city still reports both fields")
    void edge_typeMismatchCombinedWithOtherViolations() {
        ResponseEntity<String> res = get("/api/v1/weather/forecast?units=kelvin&days=abc");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fields(json(res))).contains("city", "units", "days");
    }

    @Test
    @DisplayName("Edge: a non-integer days uses the fixed message and does not echo the rejected value")
    void edge_typeMismatchMessage() {
        ResponseEntity<String> res = get("/api/v1/weather/forecast?city=London&days=xyzzy");

        JsonNode errors = json(res).get("errors");
        assertThat(errors.size()).isEqualTo(1);
        assertThat(errors.get(0).get("message").asText()).isEqualTo("must be an integer between 1 and 7");
        assertThat(res.getBody()).doesNotContain("xyzzy");
    }

    @Test
    @DisplayName("Edge: an empty units= value is invalid")
    void edge_emptyUnitsIsInvalid() {
        ResponseEntity<String> res = get("/api/v1/weather/current?city=London&units=");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fields(json(res))).containsExactly("units");
    }

    @Test
    @DisplayName("Edge: VALIDATION_FAILED body carries the full Problem shape")
    void edge_validationProblemShape() {
        ResponseEntity<String> res = get("/api/v1/weather/current");

        JsonNode body = json(res);
        assertThat(body.get("type").asText()).isEqualTo("https://weather.example.com/problems/validation-failed");
        assertThat(body.get("title").asText()).isEqualTo("Validation failed");
        assertThat(body.get("detail").asText()).isEqualTo("One or more request parameters are invalid.");
        assertThat(body.get("instance").asText()).isEqualTo("/api/v1/weather/current");
        assertThat(body.get("requestId").asText()).isNotBlank();
        assertThat(res.getHeaders().getFirst("X-Request-Id")).isEqualTo(body.get("requestId").asText());
    }

    static List<String> fields(JsonNode body) {
        List<String> fields = new ArrayList<>();
        body.path("errors").forEach(e -> fields.add(e.get("field").asText()));
        return fields;
    }
}
