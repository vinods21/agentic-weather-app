package com.example.weather.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.ApiIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** AC-16: both base URLs point at a loopback port where nothing listens. */
@ExtendWith(OutputCaptureExtension.class)
class UpstreamUnreachableIT extends ApiIntegrationTestBase {

    private static final int CLOSED_PORT = closedPort();

    /** A port that was free a moment ago: bind, read the port, close. */
    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void unreachableUpstream(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + CLOSED_PORT;
        registry.add("weather.provider.type", () -> "open-meteo");
        registry.add("weather.provider.open-meteo.geocoding-base-url", () -> url);
        registry.add("weather.provider.open-meteo.forecast-base-url", () -> url);
        registry.add("weather.provider.connect-timeout", () -> "1s");
        registry.add("weather.provider.read-timeout", () -> "1s");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/v1/weather/current?city=London", "/api/v1/weather/forecast?city=London&days=3"})
    @DisplayName("AC-16: nothing listening on the upstream port gives 503 UPSTREAM_UNAVAILABLE for current and forecast")
    void ac16_connectionRefusedIs503(String uri, CapturedOutput output) {
        String id = "it-rwp-16-" + UUID.randomUUID().toString().substring(0, 8);

        long start = System.nanoTime();
        ResponseEntity<String> res = get(uri, Map.of("X-Request-Id", id));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode body = json(res);
        assertThat(body.get("code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(body.get("detail").asText()).isEqualTo("The weather provider is temporarily unavailable.");
        assertThat(res.getBody()).doesNotContain("127.0.0.1:")
                .doesNotContainIgnoringCase("exception").doesNotContainIgnoringCase("refused");
        // Connect timeout 1 s bounds the single (geocoding) call; generous margin for slow hosts.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));

        // AC-21: one adapter WARN line naming the geocoding call and the exception class.
        assertThat(output.getAll().lines()
                .filter(l -> l.contains("requestId=" + id + "]") && l.contains("Upstream call failed")))
                .singleElement()
                .satisfies(l -> assertThat(l).contains("call=geocoding").contains("exception=")
                        .containsPattern("elapsedMs=\\d+").doesNotContain("name="));
    }
}
