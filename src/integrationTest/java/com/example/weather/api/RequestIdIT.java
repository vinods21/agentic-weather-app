package com.example.weather.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.ApiIntegrationTestBase;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** X-Request-Id echo / generation on success and error responses (AC-15, AC-16). */
class RequestIdIT extends ApiIntegrationTestBase {

    private static final String SUCCESS_CURRENT = "/api/v1/weather/current?city=London";
    private static final String SUCCESS_FORECAST = "/api/v1/weather/forecast?city=London";
    private static final String SUCCESS_OPENAPI = "/api/v1/openapi.json";
    private static final String ERROR_400 = "/api/v1/weather/current";
    private static final String ERROR_404_CITY = "/api/v1/weather/forecast?city=Atlantis";
    private static final String ERROR_404_PATH = "/api/v1/does-not-exist";

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {SUCCESS_CURRENT, SUCCESS_FORECAST, SUCCESS_OPENAPI})
    @DisplayName("AC-15: a valid client X-Request-Id is echoed on success responses")
    void ac15_echoedOnSuccess(String uri) {
        ResponseEntity<String> res = get(uri, Map.of("X-Request-Id", "ui-abc-123"));

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(res.getHeaders().get("X-Request-Id")).containsExactly("ui-abc-123");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {ERROR_400, ERROR_404_CITY, ERROR_404_PATH})
    @DisplayName("AC-15: a valid client X-Request-Id is echoed in the header and body requestId on errors")
    void ac15_echoedOnError(String uri) {
        ResponseEntity<String> res = get(uri, Map.of("X-Request-Id", "ui-abc-123"));

        assertThat(res.getStatusCode().isError()).isTrue();
        assertThat(res.getHeaders().get("X-Request-Id")).containsExactly("ui-abc-123");
        assertThat(json(res).get("requestId").asText()).isEqualTo("ui-abc-123");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("AC-15: the id is echoed on a 405 response too")
    void ac15_echoedOn405() {
        ResponseEntity<String> res = exchange(HttpMethod.POST, SUCCESS_CURRENT, Map.of("X-Request-Id", "ui-abc-123"));

        assertThat(res.getHeaders().getFirst("X-Request-Id")).isEqualTo("ui-abc-123");
        assertThat(json(res).get("requestId").asText()).isEqualTo("ui-abc-123");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {SUCCESS_CURRENT, SUCCESS_FORECAST, SUCCESS_OPENAPI})
    @DisplayName("AC-16: without X-Request-Id a fresh UUID is generated on success responses")
    void ac16_generatedWhenMissingOnSuccess(String uri) {
        ResponseEntity<String> res = get(uri);

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertUuid(res.getHeaders().getFirst("X-Request-Id"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {ERROR_400, ERROR_404_CITY, ERROR_404_PATH})
    @DisplayName("AC-16: without X-Request-Id errors carry the same generated UUID in header and body")
    void ac16_generatedWhenMissingOnError(String uri) {
        ResponseEntity<String> res = get(uri);

        String header = res.getHeaders().getFirst("X-Request-Id");
        assertUuid(header);
        assertThat(json(res).get("requestId").asText()).isEqualTo(header);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {SUCCESS_CURRENT, ERROR_400, ERROR_404_CITY})
    @DisplayName("AC-16: an invalid X-Request-Id ('bad id!') is replaced by a generated UUID")
    void ac16_invalidIdIsReplaced(String uri) {
        ResponseEntity<String> res = get(uri, Map.of("X-Request-Id", "bad id!"));

        String header = res.getHeaders().getFirst("X-Request-Id");
        assertThat(header).isNotEqualTo("bad id!");
        assertUuid(header);
        if (res.getStatusCode().isError()) {
            assertThat(json(res).get("requestId").asText()).isEqualTo(header);
        }
    }

    @ParameterizedTest(name = "length {0}")
    @ValueSource(ints = {64, 65})
    @DisplayName("AC-16 (edge): 64 characters is echoed, 65 characters is replaced")
    void ac16_edge_lengthLimit(int length) {
        String id = "a".repeat(length);
        String header = get(SUCCESS_CURRENT, Map.of("X-Request-Id", id)).getHeaders().getFirst("X-Request-Id");

        if (length <= 64) {
            assertThat(header).isEqualTo(id);
        } else {
            assertUuid(header);
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("AC-16 (edge): two requests without an id get different generated ids")
    void ac16_edge_idsAreFresh() {
        String a = get(SUCCESS_CURRENT).getHeaders().getFirst("X-Request-Id");
        String b = get(SUCCESS_CURRENT).getHeaders().getFirst("X-Request-Id");

        assertThat(a).isNotEqualTo(b);
    }

    private static void assertUuid(String value) {
        assertThat(value).isNotBlank();
        assertThat(UUID.fromString(value).toString()).isEqualTo(value.toLowerCase());
    }
}
