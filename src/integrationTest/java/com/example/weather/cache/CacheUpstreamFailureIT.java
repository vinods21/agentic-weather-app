package com.example.weather.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.CachingIntegrationTestBase;
import com.example.weather.support.FakeOpenMeteo;
import com.example.weather.support.FakeOpenMeteo.Response;
import com.example.weather.support.FakeOpenMeteo.Route;
import java.time.Duration;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Upstream failures are never cached (AC-16, AC-17). */
class CacheUpstreamFailureIT extends CachingIntegrationTestBase {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    static Stream<Arguments> geocodingFailures() {
        return Stream.of(
                Arguments.of("HTTP 500", Response.status(500, "{\"error\":true}")),
                // Read timeout is 1 s (RealProviderInitializer).
                Arguments.of("past the read timeout", Response.ok(FakeOpenMeteo.LONDON_GEOCODING)
                        .delayed(Duration.ofMillis(1600))),
                Arguments.of("invalid JSON", Response.ok("{\"results\":[{\"name\":")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("geocodingFailures")
    @DisplayName("AC-16: a failed geocoding call gives 503 and writes no key; after reset the repeat is 200 with a new GEOCODING call")
    void ac16_geocodingFailureIsNotCached(String label, Response failure) {
        fake.stub(Route.GEOCODING, failure);

        assertUnavailable(get("/api/v1/weather/current?city=London"));
        assertThat(received(Route.GEOCODING)).hasSize(1);
        assertThat(runKeys()).isEmpty();

        fake.reset();
        ResponseEntity<String> retry = get("/api/v1/weather/current?city=London");

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(received(Route.GEOCODING)).hasSize(1);
        assertThat(received(Route.CURRENT)).hasSize(1);
    }

    @Test
    @DisplayName("AC-17: CURRENT 500 gives 503; only the geocoding entry exists; the repeat makes 0 GEOCODING + 1 CURRENT call")
    void ac17_currentFailureKeepsGeocodingOnly() {
        fake.stub(Route.CURRENT, Response.status(500, "{\"error\":true}"));

        assertUnavailable(get("/api/v1/weather/current?city=London"));
        assertThat(runKeys()).containsExactly(cacheKeys.geocoding("London"));

        fake.reset();
        ResponseEntity<String> retry = get("/api/v1/weather/current?city=London");

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(retry).get("temperature").asDouble()).isEqualTo(11.2);
        assertThat(received(Route.GEOCODING)).isEmpty();
        assertThat(received(Route.CURRENT)).hasSize(1);
        assertThat(runKeys()).containsExactlyInAnyOrder(cacheKeys.geocoding("London"), cacheKeys.current(LONDON));
    }

    static Stream<Arguments> dailyFailures() {
        return Stream.of(
                Arguments.of("HTTP 500", Response.status(500, "{\"error\":true}")),
                Arguments.of("short days array", Response.ok(FakeOpenMeteo.json(FakeOpenMeteo.daily(TODAY, 2)))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dailyFailures")
    @DisplayName("AC-17: a failed DAILY call gives 503; only the geocoding entry exists; the repeat makes 0 GEOCODING + 1 DAILY call")
    void ac17_dailyFailureKeepsGeocodingOnly(String label, Response failure) {
        fake.stub(Route.DAILY, failure);

        assertUnavailable(get("/api/v1/weather/forecast?city=London&days=3"));
        assertThat(runKeys()).containsExactly(cacheKeys.geocoding("London"));

        fake.reset();
        ResponseEntity<String> retry = get("/api/v1/weather/forecast?city=London&days=3");

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(retry).get("days")).hasSize(3);
        assertThat(received(Route.GEOCODING)).isEmpty();
        assertThat(received(Route.DAILY)).hasSize(1);
        assertThat(runKeys()).containsExactlyInAnyOrder(cacheKeys.geocoding("London"),
                cacheKeys.forecast(LONDON, TODAY, 3));
    }

    private static void assertUnavailable(ResponseEntity<String> res) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(json(res).get("code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
    }
}
