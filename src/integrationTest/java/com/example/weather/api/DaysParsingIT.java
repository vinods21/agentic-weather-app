package com.example.weather.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.ApiIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Over-the-wire parse strictness of {@code days} after the D-1 fix (AC-5, AC-7, AC-8). */
class DaysParsingIT extends ApiIntegrationTestBase {

    @ParameterizedTest(name = "days={0}")
    @ValueSource(strings = {"0x3", "1%202", "%233", "3.0", "1e0", "99999999999", "%D9%A3", "%EF%BC%93"})
    @DisplayName("AC-7 (D-1 edge): non-decimal days values give 400 with only the fixed non-integer message")
    void ac7_nonDecimalDaysRejected(String days) {
        ResponseEntity<String> res = get("/api/v1/weather/forecast?city=London&days=" + days);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode errors = json(res).get("errors");
        assertThat(errors.size()).isEqualTo(1);
        assertThat(errors.get(0).get("field").asText()).isEqualTo("days");
        assertThat(errors.get(0).get("message").asText()).isEqualTo("must be an integer between 1 and 7");
        assertThat(res.getBody()).doesNotContain("java.").doesNotContain("Failed to convert");
    }

    @Test
    @DisplayName("AC-7 (D-1 edge): a repeated days parameter is rejected, not silently resolved")
    void ac7_repeatedDaysRejected() {
        ResponseEntity<String> res = get("/api/v1/weather/forecast?city=London&days=2&days=3");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ValidationIT.fields(json(res))).containsExactly("days");
    }

    @ParameterizedTest(name = "days={0}")
    @ValueSource(strings = {"03", "%2B3", "%203%20"})
    @DisplayName("AC-5 (D-1 edge): leading zero, plus sign and surrounding whitespace still mean 3 days")
    void ac5_lenientDecimalFormsAccepted(String days) {
        ResponseEntity<String> res = get("/api/v1/weather/forecast?city=London&days=" + days);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(res).get("days").size()).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-8 (D-1): days=0x3 with a missing city and bad units reports all three fields")
    void ac8_hexDaysCollectedWithOthers() {
        ResponseEntity<String> res = get("/api/v1/weather/forecast?units=kelvin&days=0x3");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ValidationIT.fields(json(res))).containsExactlyInAnyOrder("city", "units", "days");
    }
}
