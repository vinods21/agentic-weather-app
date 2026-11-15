package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.ApiIntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** AC-29: CORS preflights for the favourites write methods with an allow-list (own context, creates no rows). */
@TestPropertySource(properties = {
    "weather.api.cors.allowed-origins=http://ui.example",
    "spring.datasource.hikari.maximum-pool-size=3",
})
class FavouritesCorsIT extends ApiIntegrationTestBase {

    private static final String ALLOWED = "http://ui.example";
    private static final String ITEM = "/api/v1/favourites/" + UUID.randomUUID();

    private ResponseEntity<String> preflight(String origin, String method, String path) {
        return exchange(HttpMethod.OPTIONS, path, Map.of(
                HttpHeaders.ORIGIN, origin,
                HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method,
                HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type, x-client-id"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"POST", "PATCH", "PUT", "DELETE"})
    @DisplayName("AC-29: a preflight from the allowed origin for a write method with content-type and x-client-id is 200 without credentials")
    void ac29_allowedOriginPreflight(String method) {
        ResponseEntity<String> res = preflight(ALLOWED, method, ITEM);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED);
        assertThat(res.getHeaders().getAccessControlAllowMethods()).contains(HttpMethod.valueOf(method));
        List<String> allowedHeaders = res.getHeaders().getAccessControlAllowHeaders().stream()
                .map(String::toLowerCase).toList();
        assertThat(allowedHeaders).contains("content-type", "x-client-id");
        assertThat(res.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNotEqualTo("true");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"POST", "PATCH", "PUT", "DELETE"})
    @DisplayName("AC-29: the same preflight from http://evil.example is 403 without ACAO")
    void ac29_otherOriginRejected(String method) {
        ResponseEntity<String> res = preflight("http://evil.example", method, ITEM);

        assertThat(res.getStatusCode().value()).isEqualTo(403);
        assertThat(res.getHeaders().containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isFalse();
    }

    @ParameterizedTest(name = "{0} {1}")
    @ValueSource(strings = {"POST /api/v1/favourites", "GET /api/v1/favourites", "PUT /api/v1/favourites/order"})
    @DisplayName("AC-29 (edge): preflights on the collection and the reorder path are allowed too")
    void ac29_edge_otherPaths(String methodAndPath) {
        String[] parts = methodAndPath.split(" ");

        ResponseEntity<String> res = preflight(ALLOWED, parts[0], parts[1]);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getAccessControlAllowMethods()).contains(HttpMethod.valueOf(parts[0]));
    }

    @Test
    @DisplayName("AC-29 (edge): an actual cross-origin DELETE gets ACAO and exposes X-Request-Id and Location")
    void ac29_edge_actualRequestExposesHeaders() {
        ResponseEntity<String> res = exchange(HttpMethod.DELETE, ITEM, Map.of(
                HttpHeaders.ORIGIN, ALLOWED, "X-Client-Id", "it-fav-cors"));

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED);
        assertThat(res.getHeaders().getAccessControlExposeHeaders().stream().map(String::toLowerCase).toList())
                .contains("x-request-id", "location");
        assertThat(res.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNotEqualTo("true");
    }

    @Test
    @DisplayName("AC-29 (edge): a preflight for TRACE from the allowed origin is still rejected")
    void ac29_edge_traceRejected() {
        assertThat(preflight(ALLOWED, "TRACE", ITEM).getStatusCode().value()).isEqualTo(403);
    }
}
