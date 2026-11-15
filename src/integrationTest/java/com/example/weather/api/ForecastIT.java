package com.example.weather.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.example.weather.support.ApiIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** GET /api/v1/weather/forecast against the stub provider (AC-5, AC-6, AC-10). */
class ForecastIT extends ApiIntegrationTestBase {

    @Test
    @DisplayName("AC-5: days=3 returns 3 consecutive days from today's UTC date with valid values")
    void ac5_threeDayForecast() {
        LocalDate before = LocalDate.now(ZoneOffset.UTC);
        ResponseEntity<String> res = get("/api/v1/weather/forecast?city=London&days=3");
        LocalDate after = LocalDate.now(ZoneOffset.UTC);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        JsonNode body = json(res);
        assertThat(body.at("/location/name").asText()).isEqualTo("London");
        JsonNode days = body.get("days");
        assertThat(days.isArray()).isTrue();
        assertThat(days.size()).isEqualTo(3);

        LocalDate first = LocalDate.parse(days.get(0).get("date").asText());
        assertThat(first).isIn(before, after); // tolerate the UTC-midnight race
        for (int i = 0; i < days.size(); i++) {
            JsonNode d = days.get(i);
            assertThat(LocalDate.parse(d.get("date").asText())).isEqualTo(first.plusDays(i));
            assertThat(d.get("minTemperature").asDouble()).isLessThanOrEqualTo(d.get("maxTemperature").asDouble());
            assertThat(d.get("precipitationProbability").isIntegralNumber()).isTrue();
            assertThat(d.get("precipitationProbability").asInt()).isBetween(0, 100);
            assertThat(d.at("/condition/code").asText()).isIn(CurrentWeatherIT.CONDITION_CODES);
            assertThat(d.at("/condition/description").asText()).isNotBlank();
        }
    }

    @Test
    @DisplayName("AC-6: without days the forecast has 5 entries and metric units")
    void ac6_defaultsToFiveDaysMetric() {
        ResponseEntity<String> res = get("/api/v1/weather/forecast?city=London");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(res);
        assertThat(body.get("days").size()).isEqualTo(5);
        assertThat(body.get("units").asText()).isEqualTo("metric");
    }

    @ParameterizedTest(name = "days={0}")
    @ValueSource(ints = {1, 7})
    @DisplayName("AC-5 (edge): the inclusive bounds days=1 and days=7 are accepted")
    void ac5_edge_boundaryDays(int days) {
        ResponseEntity<String> res = get("/api/v1/weather/forecast?city=London&days=" + days);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(res).get("days").size()).isEqualTo(days);
    }

    @Test
    @DisplayName("AC-10: unknown city Atlantis on forecast is 404 LOCATION_NOT_FOUND naming the city")
    void ac10_unknownCityForecast() {
        ResponseEntity<String> res = get("/api/v1/weather/forecast?city=Atlantis");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode body = json(res);
        assertThat(body.get("code").asText()).isEqualTo("LOCATION_NOT_FOUND");
        assertThat(body.get("detail").asText()).contains("Atlantis");
    }

    @Test
    @DisplayName("Edge: imperial forecast temperatures are converted from metric")
    void edge_imperialForecast() {
        JsonNode metric = json(get("/api/v1/weather/forecast?city=London&days=7"));
        JsonNode imperial = json(get("/api/v1/weather/forecast?city=London&days=7&units=imperial"));

        assertThat(imperial.get("units").asText()).isEqualTo("imperial");
        for (int i = 0; i < 7; i++) {
            JsonNode m = metric.get("days").get(i);
            JsonNode f = imperial.get("days").get(i);
            assertThat(f.get("minTemperature").asDouble())
                    .isCloseTo(m.get("minTemperature").asDouble() * 9 / 5 + 32, within(0.1));
            assertThat(f.get("maxTemperature").asDouble())
                    .isCloseTo(m.get("maxTemperature").asDouble() * 9 / 5 + 32, within(0.1));
            assertThat(f.get("precipitationProbability")).isEqualTo(m.get("precipitationProbability"));
        }
    }

    @Test
    @DisplayName("Edge: an empty days= value is treated as absent (default 5), as documented in the spec")
    void edge_emptyDaysIsDefault() {
        ResponseEntity<String> res = get("/api/v1/weather/forecast?city=London&days=");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(res).get("days").size()).isEqualTo(5);
    }
}
