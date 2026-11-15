package com.example.weather.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.example.weather.provider.openmeteo.OpenMeteoWeatherProvider;
import com.example.weather.support.FakeOpenMeteo.RecordedRequest;
import com.example.weather.support.FakeOpenMeteo.Response;
import com.example.weather.support.FakeOpenMeteo.Route;
import com.example.weather.support.FakeOpenMeteo;
import com.example.weather.support.OpenApiSchemaAssert;
import com.example.weather.support.RealProviderIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Current weather through the real Open-Meteo adapter against the fake upstream (AC-6, 7, 9, 10, 22). */
@ExtendWith(OutputCaptureExtension.class)
class RealProviderCurrentIT extends RealProviderIntegrationTestBase {

    /** Log levels INFO and above, as printed by the default pattern ("%5p"). */
    private static final Pattern INFO_OR_ABOVE = Pattern.compile("\\b(INFO|WARN|ERROR)\\b");

    @Autowired
    WeatherProvider provider;

    @Test
    @DisplayName("AC-6: the real provider is wired for this context (sanity)")
    void ac6_realProviderIsWired() {
        assertThat(provider).isInstanceOf(OpenMeteoWeatherProvider.class);
    }

    @Test
    @DisplayName("AC-6: current weather maps the fake geocoding and current fixtures into the v1 body (schema-conformant)")
    void ac6_currentWeatherFromUpstream() {
        Instant before = Instant.now().minusSeconds(1);
        ResponseEntity<String> res = get("/api/v1/weather/current?city=London");
        Instant after = Instant.now().plusSeconds(1);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(res);
        JsonNode loc = body.get("location");
        assertThat(loc.get("name").asText()).isEqualTo("London");
        assertThat(loc.get("country").asText()).isEqualTo("GB");
        assertThat(loc.get("latitude").asDouble()).isEqualTo(51.50853);
        assertThat(loc.get("longitude").asDouble()).isEqualTo(-0.12574);
        assertThat(body.get("units").asText()).isEqualTo("metric");
        assertThat(body.get("temperature").asDouble()).isEqualTo(11.2);
        assertThat(body.get("feelsLike").asDouble()).isEqualTo(9.8);
        assertThat(body.get("humidity").isIntegralNumber()).isTrue();
        assertThat(body.get("humidity").asInt()).isEqualTo(81);
        assertThat(body.get("windSpeed").asDouble()).isEqualTo(4.1);
        assertThat(body.get("windDirection").asInt()).isEqualTo(230);
        assertThat(body.at("/condition/code").asText()).isEqualTo("CLOUDY");
        assertThat(body.at("/condition/description").asText()).isNotBlank();

        String observedAt = body.get("observedAt").asText();
        assertThat(observedAt).endsWith("Z");
        // From the service clock, not the upstream's "2026-11-15T10:00".
        assertThat(Instant.parse(observedAt)).isBetween(before, after);

        OpenApiSchemaAssert schemas = new OpenApiSchemaAssert(json(get("/api/v1/openapi.json")));
        schemas.assertConforms(body, "CurrentWeather");

        assertThat(received(Route.GEOCODING)).hasSize(1);
        RecordedRequest geocoding = received(Route.GEOCODING).get(0);
        assertThat(geocoding.param("name")).isEqualTo("London");
        assertThat(geocoding.param("count")).isEqualTo("1");
        assertThat(geocoding.param("language")).isEqualTo("en");
        assertThat(geocoding.param("format")).isEqualTo("json");

        assertThat(received(Route.CURRENT)).hasSize(1);
        RecordedRequest current = received(Route.CURRENT).get(0);
        assertThat(current.param("latitude")).isEqualTo("51.50853");
        assertThat(current.param("longitude")).isEqualTo("-0.12574");
        assertThat(List.of(current.param("current").split(","))).containsExactlyInAnyOrder("temperature_2m",
                "apparent_temperature", "relative_humidity_2m", "wind_speed_10m", "wind_direction_10m",
                "weather_code");
        assertThat(current.param("timezone")).isEqualTo("UTC");
    }

    @Test
    @DisplayName("AC-7: imperial converts in WeatherService (52.2 F, 9.2 mph); the upstream is always asked for celsius and m/s")
    void ac7_imperialConvertedLocallyUpstreamAlwaysMetric() {
        ResponseEntity<String> metric = get("/api/v1/weather/current?city=London");
        ResponseEntity<String> imperial = get("/api/v1/weather/current?city=London&units=imperial");

        assertThat(metric.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(imperial.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(imperial);
        assertThat(body.get("units").asText()).isEqualTo("imperial");
        assertThat(body.get("temperature").asDouble()).isCloseTo(52.2, within(0.1));
        assertThat(body.get("windSpeed").asDouble()).isCloseTo(9.2, within(0.1));
        assertThat(body.get("feelsLike").asDouble()).isCloseTo(9.8 * 9 / 5 + 32, within(0.1));
        assertThat(body.get("humidity").asInt()).isEqualTo(81);

        List<RecordedRequest> forecastCalls = received(Route.CURRENT);
        assertThat(forecastCalls).hasSize(2);
        for (RecordedRequest r : forecastCalls) {
            assertThat(r.param("temperature_unit")).isEqualTo("celsius");
            assertThat(r.param("wind_speed_unit")).isEqualTo("ms");
        }
    }

    @Test
    @DisplayName("AC-9: city 'london' is sent as name=london and the response uses the upstream's canonical 'London'")
    void ac9_lowerCaseCity() {
        ResponseEntity<String> res = get("/api/v1/weather/current?city=london");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(res).at("/location/name").asText()).isEqualTo("London");
        assertThat(received(Route.GEOCODING)).singleElement()
                .satisfies(r -> assertThat(r.param("name")).isEqualTo("london"));
    }

    @Test
    @DisplayName("AC-9: city '%20%20London%20' is stripped by the API layer and sent as name=London")
    void ac9_paddedCity() {
        ResponseEntity<String> res = get("/api/v1/weather/current?city=%20%20London%20");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(res).at("/location/name").asText()).isEqualTo("London");
        assertThat(received(Route.GEOCODING)).singleElement()
                .satisfies(r -> assertThat(r.param("name")).isEqualTo("London"));
    }

    @Test
    @DisplayName("AC-10: 'São Paulo&count=50' reaches geocoding as one name value; count stays 1 (no parameter injection)")
    void ac10_cityCannotInjectQueryParameters() {
        fake.stub(Route.GEOCODING, Response.ok(FakeOpenMeteo.LONDON_GEOCODING));

        ResponseEntity<String> res = get("/api/v1/weather/current?city=S%C3%A3o%20Paulo%26count%3D50");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        RecordedRequest geocoding = received(Route.GEOCODING).get(0);
        assertThat(geocoding.params().get("name")).containsExactly("São Paulo&count=50");
        assertThat(geocoding.params().get("count")).containsExactly("1");
        assertThat(geocoding.params().keySet()).containsExactlyInAnyOrder("name", "count", "language", "format");
        assertThat(geocoding.rawQuery()).doesNotContain("count=50");
    }

    @Test
    @DisplayName("AC-10 (edge): a city containing '#', '+' and '/' is sent as one literal name value")
    void ac10_edge_reservedCharactersAreEncoded() {
        fake.stub(Route.GEOCODING, Response.ok(FakeOpenMeteo.LONDON_GEOCODING));

        ResponseEntity<String> res = get("/api/v1/weather/current?city=a%2Bb%23c%2Fd%3Fe");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        RecordedRequest geocoding = received(Route.GEOCODING).get(0);
        assertThat(geocoding.path()).isEqualTo("/v1/search");
        assertThat(geocoding.params().get("name")).containsExactly("a+b#c/d?e");
        assertThat(geocoding.params().get("count")).containsExactly("1");
    }

    @Test
    @DisplayName("AC-22: a successful real-provider request logs no upstream URL with a query string at INFO or above")
    void ac22_noQueryStringsLoggedOnSuccess(CapturedOutput output) {
        String id = "it-rwp-22-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(get("/api/v1/weather/current?city=London", Map.of("X-Request-Id", id)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/weather/forecast?city=London&days=3", Map.of("X-Request-Id", id)).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        List<String> lines = output.getAll().lines().filter(l -> INFO_OR_ABOVE.matcher(l).find()).toList();
        for (String line : lines) {
            assertThat(line).doesNotContain("?name=").doesNotContain("name=London")
                    .doesNotContain("latitude=").doesNotContain("longitude=")
                    .doesNotContain("/v1/search?").doesNotContain("/v1/forecast?");
        }
        assertThat(output.getAll()).doesNotContain("latitude=51.50853");
        // The existing RequestIdFilter access line (path only, no query) is expected; the adapter logs nothing.
        assertThat(lines.stream().filter(l -> l.contains(id)))
                .isNotEmpty()
                .noneMatch(l -> l.contains("OpenMeteoWeatherProvider") || l.contains("Upstream call failed"))
                .allMatch(l -> !l.contains("city="));
    }

    @Test
    @DisplayName("AC-6 (edge): a slow but in-time upstream (300 ms) still answers 200")
    void ac6_edge_slowButInTime() {
        fake.stub(Route.CURRENT, Response.ok(FakeOpenMeteo.CURRENT_AC6).delayed(Duration.ofMillis(300)));

        assertThat(get("/api/v1/weather/current?city=London").getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
