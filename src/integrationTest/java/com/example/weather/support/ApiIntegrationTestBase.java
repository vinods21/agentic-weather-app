package com.example.weather.support;

import com.example.weather.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * HTTP helpers for the /api/v1 integration tests. Requests are sent as {@link URI} objects so
 * that percent-encoded query strings (e.g. {@code city=%20%20}) are not encoded a second time.
 *
 * <p>The API tests create no rows and no Redis keys; the base class still removes any keys
 * under the run prefix after every test.
 */
public abstract class ApiIntegrationTestBase extends IntegrationTestBase {

    protected static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    protected TestRestTemplate http;

    protected ResponseEntity<String> get(String pathAndQuery) {
        return exchange(HttpMethod.GET, pathAndQuery, Map.of());
    }

    protected ResponseEntity<String> get(String pathAndQuery, Map<String, String> headers) {
        return exchange(HttpMethod.GET, pathAndQuery, headers);
    }

    protected ResponseEntity<String> exchange(HttpMethod method, String pathAndQuery, Map<String, String> headers) {
        HttpHeaders h = new HttpHeaders();
        headers.forEach(h::set);
        return http.exchange(URI.create(pathAndQuery), method, new HttpEntity<>(h), String.class);
    }

    protected static JsonNode json(ResponseEntity<String> response) {
        try {
            return JSON.readTree(response.getBody());
        } catch (Exception e) {
            throw new AssertionError("response body is not JSON: " + response.getBody(), e);
        }
    }
}
