package com.example.weather.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.ApiIntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.DisplayName;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * CORS with no allow-list (AC-18, "unset" case). The property is forced empty so the test does
 * not depend on whatever WEATHER_API_CORS_ALLOWED_ORIGINS a local .env exports.
 */
@TestPropertySource(properties = "weather.api.cors.allowed-origins=")
class CorsDefaultDenyIT extends ApiIntegrationTestBase {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"http://localhost:5173", "http://evil.example", "https://ui.example.com"})
    @DisplayName("AC-18: with no allowed origins configured every preflight is rejected (403, no ACAO)")
    void ac18_defaultDeniesEveryOrigin(String origin) {
        ResponseEntity<String> res = exchange(HttpMethod.OPTIONS, "/api/v1/weather/current", Map.of(
                HttpHeaders.ORIGIN, origin,
                HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getHeaders().containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"http://localhost:5173", "http://evil.example"})
    @DisplayName("AC-18 (edge): an actual cross-origin GET is rejected without ACAO when nothing is allowed")
    void ac18_edge_actualCrossOriginGetRejected(String origin) {
        ResponseEntity<String> res = get("/api/v1/weather/current?city=London", Map.of(HttpHeaders.ORIGIN, origin));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getHeaders().containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isFalse();
    }
}
