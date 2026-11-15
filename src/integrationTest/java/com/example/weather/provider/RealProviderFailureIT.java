package com.example.weather.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.weather.support.FakeOpenMeteo;
import com.example.weather.support.FakeOpenMeteo.Response;
import com.example.weather.support.FakeOpenMeteo.Route;
import com.example.weather.support.RealProviderIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Upstream failures through the real adapter: 503 UPSTREAM_UNAVAILABLE with the fixed body, one
 * sanitised adapter WARN line per failed call, nothing leaked (AC-15, AC-17, AC-18, AC-21).
 */
@ExtendWith(OutputCaptureExtension.class)
class RealProviderFailureIT extends RealProviderIntegrationTestBase {

    static final String UPSTREAM_DETAIL = "The weather provider is temporarily unavailable.";
    static final String ERROR_BODY = "{\"error\":true,\"reason\":\"x\"}";

    @Autowired
    WeatherProvider provider;

    private static String requestId() {
        return "it-rwp-21-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ------------------------------------------------------------------ AC-15

    @ParameterizedTest(name = "{0} delayed 5 s")
    @EnumSource(value = Route.class, names = {"GEOCODING", "CURRENT"})
    @DisplayName("AC-15: a 5 s upstream stall with read-timeout 1s gives 503 in under 3 s")
    void ac15_readTimeoutIs503Quickly(Route stalled, CapturedOutput output) {
        String body = stalled == Route.GEOCODING ? FakeOpenMeteo.LONDON_GEOCODING : FakeOpenMeteo.CURRENT_AC6;
        fake.stub(stalled, Response.ok(body).delayed(Duration.ofSeconds(5)));
        String id = requestId();

        long start = System.nanoTime();
        ResponseEntity<String> res = get("/api/v1/weather/current?city=London", Map.of("X-Request-Id", id));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertUpstreamUnavailable(res, id);
        assertThat(elapsed).as("time to 503").isLessThan(Duration.ofSeconds(3));
        String warn = assertSingleAdapterWarn(output, id, stalled == Route.GEOCODING ? "geocoding" : "forecast");
        assertThat(warn).contains("exception=");
        if (stalled == Route.GEOCODING) {
            assertThat(received(Route.CURRENT)).as("no forecast call after a failed geocoding").isEmpty();
        }
    }

    // ------------------------------------------------------------------ AC-17

    static List<Arguments> statusCases() {
        return List.of(500, 502, 503, 429, 400).stream()
                .flatMap(s -> List.of(Arguments.of(Route.GEOCODING, s), Arguments.of(Route.CURRENT, s)).stream())
                .toList();
    }

    @ParameterizedTest(name = "{0} answers {1}")
    @MethodSource("statusCases")
    @DisplayName("AC-17: a non-2xx upstream status (500/502/503/429/400) on geocoding or forecast gives 503")
    void ac17_non2xxIs503(Route failing, int status, CapturedOutput output) {
        fake.stub(failing, Response.status(status, ERROR_BODY));
        String id = requestId();

        ResponseEntity<String> res = get("/api/v1/weather/current?city=London", Map.of("X-Request-Id", id));

        assertUpstreamUnavailable(res, id);
        String warn = assertSingleAdapterWarn(output, id, failing == Route.GEOCODING ? "geocoding" : "forecast");
        assertThat(warn).contains("status=" + status);
        assertThat(res.getBody()).doesNotContain("\"reason\"").doesNotContain("\"x\"");
    }

    @Test
    @DisplayName("AC-17: a non-2xx daily forecast call on /forecast also gives 503 with call=forecast")
    void ac17_dailyNon2xxIs503(CapturedOutput output) {
        fake.stub(Route.DAILY, Response.status(502, ERROR_BODY));
        String id = requestId();

        ResponseEntity<String> res = get("/api/v1/weather/forecast?city=London&days=3", Map.of("X-Request-Id", id));

        assertUpstreamUnavailable(res, id);
        assertThat(assertSingleAdapterWarn(output, id, "forecast")).contains("status=502");
    }

    // ------------------------------------------------------------------ AC-18

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "not json at all",
            "{\"current\":{\"temperature_2m\":11.2",
            "{\"current\":{\"apparent_temperature\":9.8,\"relative_humidity_2m\":81,\"wind_speed_10m\":4.1,"
                    + "\"wind_direction_10m\":230,\"weather_code\":3}}",
            "{\"current\":{\"temperature_2m\":null,\"apparent_temperature\":9.8,\"relative_humidity_2m\":81,"
                    + "\"wind_speed_10m\":4.1,\"wind_direction_10m\":230,\"weather_code\":3}}",
            "{\"current\":{\"temperature_2m\":11.2,\"apparent_temperature\":9.8,\"relative_humidity_2m\":81,"
                    + "\"wind_speed_10m\":4.1,\"wind_direction_10m\":230,\"weather_code\":4}}",
            "{\"current\":{\"temperature_2m\":\"11.2\",\"apparent_temperature\":9.8,\"relative_humidity_2m\":81,"
                    + "\"wind_speed_10m\":4.1,\"wind_direction_10m\":230,\"weather_code\":3}}",
            "[]"})
    @DisplayName("AC-18: invalid JSON, a missing/null/non-numeric required field or an unmapped weather code gives 503")
    void ac18_malformedForecastIs503(String body, CapturedOutput output) {
        fake.stub(Route.CURRENT, Response.ok(body));
        String id = requestId();

        ResponseEntity<String> res = get("/api/v1/weather/current?city=London", Map.of("X-Request-Id", id));

        assertUpstreamUnavailable(res, id);
        assertThat(assertSingleAdapterWarn(output, id, "forecast")).contains("invalid-response");
    }

    @Test
    @DisplayName("AC-18 (edge): a 200 with an empty body gives 503")
    void ac18_edge_emptyBodyIs503(CapturedOutput output) {
        fake.stub(Route.CURRENT, Response.ok(null));
        String id = requestId();

        ResponseEntity<String> res = get("/api/v1/weather/current?city=London", Map.of("X-Request-Id", id));

        assertUpstreamUnavailable(res, id);
        assertSingleAdapterWarn(output, id, "forecast");
    }

    @Test
    @DisplayName("AC-18 (edge): a body larger than 1 MiB gives 503 (body cap)")
    void ac18_edge_oversizeBodyIs503(CapturedOutput output) {
        String padding = "x".repeat(1024 * 1024 + 16);
        fake.stub(Route.CURRENT, Response.ok("{\"padding\":\"" + padding + "\"}"));
        String id = requestId();

        ResponseEntity<String> res = get("/api/v1/weather/current?city=London", Map.of("X-Request-Id", id));

        assertUpstreamUnavailable(res, id);
        assertThat(assertSingleAdapterWarn(output, id, "forecast")).contains("body too large");
    }

    @Test
    @DisplayName("AC-18 (edge): malformed geocoding JSON gives 503, not 404")
    void ac18_edge_malformedGeocodingIs503(CapturedOutput output) {
        fake.stub(Route.GEOCODING, Response.ok("<html>maintenance</html>"));
        String id = requestId();

        ResponseEntity<String> res = get("/api/v1/weather/current?city=London", Map.of("X-Request-Id", id));

        assertUpstreamUnavailable(res, id);
        assertSingleAdapterWarn(output, id, "geocoding");
    }

    // ------------------------------------------------------------------ AC-21

    @Test
    @DisplayName("AC-21: the WARN line for X-Request-Id it-rwp-21 names the call, status and elapsed ms, without query strings")
    void ac21_warnLineContentForFixedRequestId(CapturedOutput output) {
        fake.stub(Route.CURRENT, Response.status(500, ERROR_BODY));

        ResponseEntity<String> res = get("/api/v1/weather/current?city=London", Map.of("X-Request-Id", "it-rwp-21"));

        assertUpstreamUnavailable(res, "it-rwp-21");
        String warn = assertSingleAdapterWarn(output, "it-rwp-21", "forecast");
        assertThat(warn).contains("status=500").containsPattern("elapsedMs=\\d+").contains("path=/v1/forecast");
    }

    @Test
    @DisplayName("AC-21: the WeatherProviderUnavailableException thrown by the adapter has no cause and no query string")
    void ac21_exceptionMessageIsSanitised() {
        fake.stub(Route.GEOCODING, Response.status(500, ERROR_BODY));
        assertSanitisedException(() -> provider.current("London"));

        fake.reset();
        fake.stub(Route.CURRENT, Response.ok("{}").delayed(Duration.ofSeconds(5)));
        assertSanitisedException(() -> provider.current("London"));

        fake.reset();
        fake.stub(Route.DAILY, Response.ok("{\"daily\":{}}"));
        assertSanitisedException(() -> provider.forecast("London", LocalDate.now(), 3));
    }

    private static void assertSanitisedException(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(com.example.weather.provider.WeatherProviderUnavailableException.class)
                .hasNoCause()
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("?").doesNotContain("name=").doesNotContain("latitude=")
                        .doesNotContain("longitude=").doesNotContain("London").doesNotContain("127.0.0.1"));
    }

    // ------------------------------------------------------------------ helpers

    private static void assertUpstreamUnavailable(ResponseEntity<String> res, String id) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode body = json(res);
        assertThat(body.get("code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(body.get("status").asInt()).isEqualTo(503);
        assertThat(body.get("detail").asText()).isEqualTo(UPSTREAM_DETAIL);
        assertThat(body.get("requestId").asText()).isEqualTo(id);
        // AC-21: no upstream host, URL, status or exception text in the body.
        assertThat(res.getBody())
                .doesNotContain("127.0.0.1")
                .doesNotContain("/v1/search").doesNotContain("/v1/forecast")
                .doesNotContainIgnoringCase("open-meteo")
                .doesNotContainIgnoringCase("exception")
                .doesNotContain("latitude").doesNotContain("timed out");
    }

    /**
     * AC-21: exactly one adapter WARN line for the request id, naming the call, an outcome and the
     * elapsed time; no line for that request carries an upstream query string.
     */
    private static String assertSingleAdapterWarn(CapturedOutput output, String id, String call) {
        List<String> forRequest = output.getAll().lines().filter(l -> l.contains("requestId=" + id + "]")).toList();
        List<String> adapterWarns = forRequest.stream()
                .filter(l -> l.contains("WARN") && l.contains("Upstream call failed"))
                .toList();
        assertThat(adapterWarns).as("adapter WARN lines for %s", id).hasSize(1);
        String warn = adapterWarns.get(0);
        assertThat(warn).contains("call=" + call).containsPattern("elapsedMs=\\d+")
                .containsPattern("outcome=(status=\\d+|exception=\\S+|invalid-response reason=)");
        for (String line : forRequest) {
            assertThat(line).doesNotContain("name=").doesNotContain("latitude=").doesNotContain("longitude=")
                    .doesNotContain("?");
        }
        assertThat(output.getAll()).doesNotContain("latitude=").doesNotContain("longitude=")
                .doesNotContain("/v1/search?").doesNotContain("/v1/forecast?");
        return warn;
    }
}
