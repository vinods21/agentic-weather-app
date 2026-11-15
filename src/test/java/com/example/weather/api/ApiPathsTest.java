package com.example.weather.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** Unit test: the /api/ path guard (A-10, AC-23). No Spring context, no Docker. */
class ApiPathsTest {

    private static MockHttpServletRequest request(String contextPath, String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", uri);
        r.setContextPath(contextPath);
        return r;
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/weather/current", "/api/", "/api/v2/x", "/api/v1/does-not-exist"})
    @DisplayName("A-10 (unit): paths under /api/ are API paths")
    void apiPaths(String uri) {
        assertThat(ApiPaths.isApi(request("", uri))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/api", "/apix/v1", "/", "/error", "/API/v1/weather/current"})
    @DisplayName("AC-23 (unit): paths outside /api/ (incl. /api without slash, /apix, /error) are not API paths")
    void nonApiPaths(String uri) {
        assertThat(ApiPaths.isApi(request("", uri))).isFalse();
    }

    @Test
    @DisplayName("A-10 (unit): the context path is removed before the check")
    void contextPathStripped() {
        MockHttpServletRequest r = request("/weather", "/weather/api/v1/weather/current");
        assertThat(ApiPaths.path(r)).isEqualTo("/api/v1/weather/current");
        assertThat(ApiPaths.isApi(r)).isTrue();
        assertThat(ApiPaths.isApi(request("/weather", "/weather/actuator/health"))).isFalse();
    }

    @Test
    @DisplayName("AC-11 (unit): path never includes the query string")
    void pathExcludesQuery() {
        MockHttpServletRequest r = request("", "/api/v1/does-not-exist");
        r.setQueryString("token=secret");
        assertThat(ApiPaths.path(r)).isEqualTo("/api/v1/does-not-exist");
    }
}
