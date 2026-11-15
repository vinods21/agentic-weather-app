package com.example.weather.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.ApiIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** 404 NOT_FOUND and 405 METHOD_NOT_ALLOWED under /api/ (AC-11, AC-12), plus A-10 scope. */
class RoutingErrorsIT extends ApiIntegrationTestBase {

    @Test
    @DisplayName("AC-11: unknown path under /api/v1 is 404 NOT_FOUND with instance = path")
    void ac11_unknownApiPath() {
        ResponseEntity<String> res = get("/api/v1/does-not-exist");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode body = json(res);
        assertThat(body.get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(body.get("instance").asText()).isEqualTo("/api/v1/does-not-exist");
        assertThat(body.get("status").asInt()).isEqualTo(404);
    }

    @Test
    @DisplayName("AC-11 (edge): instance never carries the query string")
    void ac11_edge_instanceHasNoQueryString() {
        ResponseEntity<String> res = get("/api/v1/does-not-exist?token=secret");

        assertThat(json(res).get("instance").asText()).isEqualTo("/api/v1/does-not-exist");
        assertThat(res.getBody()).doesNotContain("secret");
    }

    @Test
    @DisplayName("AC-12: POST on current weather is 405 METHOD_NOT_ALLOWED with Allow including GET")
    void ac12_postIsMethodNotAllowed() {
        ResponseEntity<String> res = exchange(HttpMethod.POST, "/api/v1/weather/current?city=London", Map.of());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(json(res).get("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(res.getHeaders().getAllow()).contains(HttpMethod.GET);
    }

    @Test
    @DisplayName("AC-12 (edge): DELETE on forecast is also 405 METHOD_NOT_ALLOWED")
    void ac12_edge_deleteIsMethodNotAllowed() {
        ResponseEntity<String> res = exchange(HttpMethod.DELETE, "/api/v1/weather/forecast?city=London", Map.of());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(json(res).get("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(res.getHeaders().getAllow()).contains(HttpMethod.GET);
    }

    @Test
    @DisplayName("A-10: unknown path outside /api/ keeps Spring Boot's default 404 (not Problem format)")
    void a10_nonApiPathKeepsDefault() {
        ResponseEntity<String> res = get("/not-api/does-not-exist");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        MediaType ct = res.getHeaders().getContentType();
        if (ct != null) {
            assertThat(ct.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isFalse();
        }
        assertThat(res.getBody() == null ? "" : res.getBody()).doesNotContain("\"code\"");
        assertThat(res.getHeaders().containsKey("X-Request-Id")).isFalse();
    }
}
