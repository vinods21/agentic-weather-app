package com.example.weather.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.example.weather.support.ApiIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** GET /api/v1/weather/current against the stub provider (AC-1..AC-4, AC-9, AC-10). */
class CurrentWeatherIT extends ApiIntegrationTestBase {

    static final List<String> CONDITION_CODES =
            List.of("CLEAR", "PARTLY_CLOUDY", "CLOUDY", "FOG", "DRIZZLE", "RAIN", "SNOW", "THUNDERSTORM");

    static final List<String> CURRENT_FIELDS = List.of("location", "observedAt", "units", "temperature",
            "feelsLike", "humidity", "windSpeed", "windDirection", "condition");

    @Test
    @DisplayName("AC-1: current weather for London returns the full contract body (metric, ranges, UTC observedAt)")
    void ac1_currentWeatherForLondon() {
        ResponseEntity<String> res = get("/api/v1/weather/current?city=London");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getContentType()).isNotNull();
        assertThat(res.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(res.getHeaders().getContentType().getSubtype()).isEqualTo("json");

        JsonNode body = json(res);
        for (String f : CURRENT_FIELDS) {
            assertThat(body.hasNonNull(f)).as("field %s present and non-null", f).isTrue();
        }
        JsonNode loc = body.get("location");
        assertThat(loc.get("name").asText()).isEqualTo("London");
        assertThat(loc.get("country").asText()).isEqualTo("GB");
        assertThat(loc.get("latitude").isNumber()).isTrue();
        assertThat(loc.get("longitude").isNumber()).isTrue();
        assertThat(body.get("units").asText()).isEqualTo("metric");
        assertThat(body.get("temperature").isNumber()).isTrue();
        assertThat(body.get("feelsLike").isNumber()).isTrue();
        assertThat(body.get("windSpeed").isNumber()).isTrue();
        assertThat(body.get("humidity").isIntegralNumber()).isTrue();
        assertThat(body.get("humidity").asInt()).isBetween(0, 100);
        assertThat(body.get("windDirection").isIntegralNumber()).isTrue();
        assertThat(body.get("windDirection").asInt()).isBetween(0, 359);
        assertThat(body.at("/condition/code").asText()).isIn(CONDITION_CODES);
        assertThat(body.at("/condition/description").asText()).isNotBlank();

        String observedAt = body.get("observedAt").asText();
        assertThat(observedAt).endsWith("Z");
        assertThat(Instant.parse(observedAt)).isNotNull();
    }

    @Test
    @DisplayName("AC-2: imperial temperature equals metric converted with F = C x 9/5 + 32 (within 0.1)")
    void ac2_imperialIsConvertedFromMetric() {
        JsonNode metric = json(get("/api/v1/weather/current?city=London"));
        ResponseEntity<String> res = get("/api/v1/weather/current?city=London&units=imperial");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode imperial = json(res);
        assertThat(imperial.get("units").asText()).isEqualTo("imperial");
        double c = metric.get("temperature").asDouble();
        assertThat(imperial.get("temperature").asDouble()).isCloseTo(c * 9 / 5 + 32, within(0.1));
        // Extra: feelsLike and wind speed are converted too (mph = m/s x 2.2369363).
        assertThat(imperial.get("feelsLike").asDouble())
                .isCloseTo(metric.get("feelsLike").asDouble() * 9 / 5 + 32, within(0.1));
        assertThat(imperial.get("windSpeed").asDouble())
                .isCloseTo(metric.get("windSpeed").asDouble() * 2.2369363, within(0.1));
        // Non-unit fields are unchanged.
        assertThat(imperial.get("humidity")).isEqualTo(metric.get("humidity"));
        assertThat(imperial.get("windDirection")).isEqualTo(metric.get("windDirection"));
    }

    @Test
    @DisplayName("AC-3: two identical requests return identical bodies apart from observedAt")
    void ac3_stubIsDeterministic() {
        for (String units : List.of("metric", "imperial")) {
            ObjectNode first = (ObjectNode) json(get("/api/v1/weather/current?city=London&units=" + units));
            ObjectNode second = (ObjectNode) json(get("/api/v1/weather/current?city=London&units=" + units));
            first.remove("observedAt");
            second.remove("observedAt");
            assertThat(second).as("units=%s", units).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("AC-4: surrounding whitespace in city is trimmed")
    void ac4_cityIsTrimmed() {
        ResponseEntity<String> res = get("/api/v1/weather/current?city=%20%20London%20");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(res).at("/location/name").asText()).isEqualTo("London");
    }

    @Test
    @DisplayName("AC-9: a 100-character unknown city passes validation and is 404 LOCATION_NOT_FOUND")
    void ac9_hundredCharacterCityIsInclusive() {
        ResponseEntity<String> res = get("/api/v1/weather/current?city=" + "a".repeat(100));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(json(res).get("code").asText()).isEqualTo("LOCATION_NOT_FOUND");
    }

    @Test
    @DisplayName("AC-9 (edge): 100 characters plus surrounding whitespace is still valid after trimming")
    void ac9_edge_lengthIsCheckedAfterTrimming() {
        ResponseEntity<String> res = get("/api/v1/weather/current?city=%20%20" + "a".repeat(100) + "%20");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(res).get("code").asText()).isEqualTo("LOCATION_NOT_FOUND");
    }

    @Test
    @DisplayName("AC-10: unknown city Atlantis on current weather is 404 LOCATION_NOT_FOUND naming the city")
    void ac10_unknownCityCurrent() {
        ResponseEntity<String> res = get("/api/v1/weather/current?city=Atlantis");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode body = json(res);
        assertThat(body.get("code").asText()).isEqualTo("LOCATION_NOT_FOUND");
        assertThat(body.get("status").asInt()).isEqualTo(404);
        assertThat(body.get("detail").asText()).contains("Atlantis");
        assertThat(body.has("errors")).isFalse();
    }

    @Test
    @DisplayName("Edge: city lookup is case-insensitive and returns the canonical name")
    void edge_caseInsensitiveCity() {
        ResponseEntity<String> res = get("/api/v1/weather/current?city=lONdon");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(res).at("/location/name").asText()).isEqualTo("London");
    }

    @Test
    @DisplayName("Edge: unknown query parameters are ignored (A-9)")
    void edge_unknownParametersIgnored() {
        ResponseEntity<String> res = get("/api/v1/weather/current?city=London&foo=bar&lang=xx");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Edge: a city with markup is echoed only JSON-escaped in a problem+json body")
    void edge_cityWithMarkupIsNotRenderedAsHtml() {
        ResponseEntity<String> res = get("/api/v1/weather/current?city=%3Cscript%3Ex%3C%2Fscript%3E");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(json(res).get("detail").asText()).contains("<script>x</script>");
    }
}
