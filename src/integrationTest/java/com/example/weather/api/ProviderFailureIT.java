package com.example.weather.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.example.weather.provider.WeatherProvider;
import com.example.weather.provider.WeatherProviderUnavailableException;
import com.example.weather.support.ApiIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Provider failures (AC-13, AC-14). The stub bean is wrapped in a Mockito spy so single calls
 * can be made to throw; the spy is reset after every test.
 */
@ExtendWith(OutputCaptureExtension.class)
class ProviderFailureIT extends ApiIntegrationTestBase {

    @MockitoSpyBean
    WeatherProvider provider;

    private static String uniqueId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("AC-13: provider unavailable on current weather is 503 UPSTREAM_UNAVAILABLE and logs WARN with request id")
    void ac13_currentUpstreamUnavailable(CapturedOutput output) {
        doThrow(new WeatherProviderUnavailableException("simulated upstream timeout", null))
                .when(provider).current(anyString());
        String id = uniqueId("it-ac13-current");

        ResponseEntity<String> res = get("/api/v1/weather/current?city=London", Map.of("X-Request-Id", id));

        assertUpstreamUnavailable(res, id);
        assertThat(warnLinesContaining(output, id)).as("WARN line with request id").isNotBlank()
                .as("provider message is not logged").doesNotContain("simulated upstream timeout");
    }

    @Test
    @DisplayName("AC-13: provider unavailable on forecast is 503 UPSTREAM_UNAVAILABLE and logs WARN with request id")
    void ac13_forecastUpstreamUnavailable(CapturedOutput output) {
        doThrow(new WeatherProviderUnavailableException("simulated upstream timeout", null))
                .when(provider).forecast(anyString(), any(), anyInt());
        String id = uniqueId("it-ac13-forecast");

        ResponseEntity<String> res = get("/api/v1/weather/forecast?city=London", Map.of("X-Request-Id", id));

        assertUpstreamUnavailable(res, id);
        assertThat(warnLinesContaining(output, id)).as("WARN line with request id").isNotBlank()
                .as("provider message is not logged").doesNotContain("simulated upstream timeout");
    }

    @Test
    @DisplayName("AC-14: unexpected exception is 500 INTERNAL_ERROR without leaking details; ERROR log has id and stack trace")
    void ac14_unexpectedErrorIsSanitised(CapturedOutput output) {
        doThrow(new RuntimeException("boom-secret")).when(provider).current(anyString());
        String id = uniqueId("it-ac14");

        ResponseEntity<String> res = get("/api/v1/weather/current?city=London", Map.of("X-Request-Id", id));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode body = json(res);
        assertThat(body.get("code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.get("requestId").asText()).isEqualTo(id);
        assertThat(res.getBody())
                .doesNotContain("boom-secret")
                .doesNotContain("RuntimeException")
                .doesNotContain("java.")
                .doesNotContain("com.example")
                .doesNotContain("\tat ")
                .doesNotContainIgnoringCase("exception")
                .doesNotContain("trace");

        String all = output.getAll();
        String errorLines = all.lines()
                .filter(l -> l.contains("ERROR") && l.contains(id))
                .collect(Collectors.joining("\n"));
        assertThat(errorLines).as("ERROR line with request id").isNotBlank();
        // The stack trace follows the ERROR line.
        String afterError = all.substring(all.indexOf(errorLines.lines().findFirst().orElseThrow()));
        assertThat(afterError).contains("java.lang.RuntimeException: boom-secret");
        assertThat(afterError).containsPattern("\\n\\s+at [\\w.$]+\\(");
    }

    private static void assertUpstreamUnavailable(ResponseEntity<String> res, String id) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode body = json(res);
        assertThat(body.get("code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(body.get("status").asInt()).isEqualTo(503);
        assertThat(body.get("requestId").asText()).isEqualTo(id);
        assertThat(res.getBody()).doesNotContain("simulated upstream timeout");
    }

    private static String warnLinesContaining(CapturedOutput output, String id) {
        return output.getAll().lines()
                .filter(l -> l.contains("WARN") && l.contains(id))
                .collect(Collectors.joining("\n"));
    }
}
