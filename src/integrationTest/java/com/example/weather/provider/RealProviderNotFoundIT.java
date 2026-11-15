package com.example.weather.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.FakeOpenMeteo.Response;
import com.example.weather.support.FakeOpenMeteo.Route;
import com.example.weather.support.RealProviderIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** 404 LOCATION_NOT_FOUND from the real adapter (AC-13, AC-14). */
@ExtendWith(OutputCaptureExtension.class)
class RealProviderNotFoundIT extends RealProviderIntegrationTestBase {

    @ParameterizedTest(name = "{0} with geocoding body {1}")
    @CsvSource(delimiter = '|', value = {
            "/api/v1/weather/current?city=Atlantis           | {\"generationtime_ms\":0.2}",
            "/api/v1/weather/current?city=Atlantis           | {\"results\":[]}",
            "/api/v1/weather/forecast?city=Atlantis          | {\"generationtime_ms\":0.2}",
            "/api/v1/weather/forecast?city=Atlantis&days=3   | {\"results\":[]}"})
    @DisplayName("AC-13: no geocoding results gives 404 LOCATION_NOT_FOUND naming the city and no forecast call")
    void ac13_noResultsIs404(String uri, String geocodingBody, CapturedOutput output) {
        fake.stub(Route.GEOCODING, Response.ok(geocodingBody));
        String id = "it-rwp-13-" + UUID.randomUUID().toString().substring(0, 8);

        ResponseEntity<String> res = get(uri, Map.of("X-Request-Id", id));

        assertLocationNotFound(res, "Atlantis");
        assertThat(received(Route.GEOCODING)).hasSize(1);
        assertThat(received(Route.CURRENT)).isEmpty();
        assertThat(received(Route.DAILY)).isEmpty();
        assertThat(output.getAll().lines().filter(l -> l.contains(id) && l.contains("Upstream call failed")))
                .as("not-found is not an upstream failure").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/v1/weather/current?city=Atlantis", "/api/v1/weather/forecast?city=Atlantis"})
    @DisplayName("AC-13: the fake's default (unknown city) also gives 404 for both endpoints")
    void ac13_unknownCityByDefault(String uri) {
        assertLocationNotFound(get(uri), "Atlantis");
        assertThat(received(Route.GEOCODING)).singleElement()
                .satisfies(r -> assertThat(r.param("name")).isEqualTo("Atlantis"));
    }

    @ParameterizedTest(name = "result {0}")
    @ValueSource(strings = {
            "{\"name\":\"London\",\"latitude\":51.50853,\"longitude\":-0.12574}",
            "{\"name\":\"London\",\"latitude\":51.50853,\"longitude\":-0.12574,\"country_code\":\"\"}",
            "{\"name\":\"London\",\"latitude\":51.50853,\"longitude\":-0.12574,\"country_code\":\"  \"}",
            "{\"name\":\"London\",\"latitude\":51.50853,\"longitude\":-0.12574,\"country_code\":null}"})
    @DisplayName("AC-14: a first geocoding result with no or blank country_code gives 404 LOCATION_NOT_FOUND")
    void ac14_missingCountryIs404(String result) {
        fake.stub(Route.GEOCODING, Response.ok("{\"results\":[" + result + "]}"));

        assertLocationNotFound(get("/api/v1/weather/current?city=London"), "London");
        assertThat(received(Route.CURRENT)).isEmpty();
    }

    private static void assertLocationNotFound(ResponseEntity<String> res, String city) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode body = json(res);
        assertThat(body.get("code").asText()).isEqualTo("LOCATION_NOT_FOUND");
        assertThat(body.get("detail").asText()).contains(city);
    }
}
