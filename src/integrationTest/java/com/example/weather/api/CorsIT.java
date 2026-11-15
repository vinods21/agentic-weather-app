package com.example.weather.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.ApiIntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** CORS with an allow-list configured (AC-17, AC-18 rejected origin). */
@TestPropertySource(properties = "weather.api.cors.allowed-origins=http://localhost:5173")
class CorsIT extends ApiIntegrationTestBase {

    private static final String ALLOWED = "http://localhost:5173";

    private ResponseEntity<String> preflight(String origin, String method) {
        return exchange(HttpMethod.OPTIONS, "/api/v1/weather/current", Map.of(
                HttpHeaders.ORIGIN, origin,
                HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method));
    }

    @Test
    @DisplayName("AC-17: preflight from the allowed origin is accepted and exposes X-Request-Id")
    void ac17_allowedOriginPreflight() {
        ResponseEntity<String> res = preflight(ALLOWED, "GET");

        assertThat(res.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.NO_CONTENT);
        assertThat(res.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED);
        assertThat(res.getHeaders().getAccessControlExposeHeaders())
                .anySatisfy(h -> assertThat(h).isEqualToIgnoringCase("X-Request-Id"));
        assertThat(res.getHeaders().getAccessControlAllowMethods()).contains(HttpMethod.GET);
        assertThat(res.getHeaders().getAccessControlAllowCredentials()).isFalse();
    }

    @Test
    @DisplayName("AC-17 (edge): an actual GET from the allowed origin carries ACAO and exposes X-Request-Id")
    void ac17_edge_actualRequestFromAllowedOrigin() {
        ResponseEntity<String> res = get("/api/v1/weather/current?city=London", Map.of(HttpHeaders.ORIGIN, ALLOWED));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED);
        assertThat(res.getHeaders().getAccessControlExposeHeaders())
                .anySatisfy(h -> assertThat(h).isEqualToIgnoringCase("X-Request-Id"));
    }

    @Test
    @DisplayName("AC-18: preflight from an unlisted origin is rejected (403, no ACAO header)")
    void ac18_unlistedOriginRejected() {
        ResponseEntity<String> res = preflight("http://evil.example", "GET");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getHeaders().containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isFalse();
        // Design: the request-id filter runs before CORS, so the rejection still carries the id.
        assertThat(res.getHeaders().getFirst("X-Request-Id")).isNotBlank();
    }

    @Test
    @DisplayName("AC-18 (edge): preflight for a non-allowed method (TRACE) from the allowed origin is rejected")
    void ac18_edge_disallowedMethodRejected() {
        ResponseEntity<String> res = preflight(ALLOWED, "TRACE");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getHeaders().containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isFalse();
    }
}
