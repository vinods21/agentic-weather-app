package com.example.weather.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.ApiIntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Actuator health is unaffected by /api/v1 handling (AC-23). */
class HealthRegressionIT extends ApiIntegrationTestBase {

    @Test
    @DisplayName("AC-23: /actuator/health is 200 UP, not Problem format, and has no X-Request-Id")
    void ac23_healthUnaffected() {
        ResponseEntity<String> res = get("/actuator/health", Map.of("X-Request-Id", "ui-abc-123"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(res).get("status").asText()).isEqualTo("UP");
        MediaType ct = res.getHeaders().getContentType();
        assertThat(ct).isNotNull();
        assertThat(ct.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isFalse();
        assertThat(json(res).has("code")).isFalse();
        assertThat(res.getHeaders().containsKey("X-Request-Id")).isFalse();
    }
}
