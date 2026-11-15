package com.example.weather.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.support.FakeOpenMeteo;
import com.example.weather.support.FakeOpenMeteo.RecordedRequest;
import com.example.weather.support.FakeOpenMeteo.Response;
import com.example.weather.support.FakeOpenMeteo.Route;
import com.example.weather.support.OpenApiSchemaAssert;
import com.example.weather.support.RealProviderIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.convention.TestBean;

/** Forecast through the real adapter with the service clock fixed at 2026-11-15T10:00:00Z (AC-8, AC-19, AC-20). */
class RealProviderForecastIT extends RealProviderIntegrationTestBase {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    @TestBean
    Clock clock;

    static Clock clock() {
        return Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    @DisplayName("AC-8: 3-day forecast returns the fixture days in order with RAIN, PARTLY_CLOUDY, FOG; upstream got start/end dates and timezone=UTC")
    void ac8_threeDayForecast() {
        ResponseEntity<String> res = get("/api/v1/weather/forecast?city=London&days=3");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(res);
        assertThat(body.at("/location/name").asText()).isEqualTo("London");
        assertThat(body.at("/location/country").asText()).isEqualTo("GB");
        assertThat(body.get("units").asText()).isEqualTo("metric");
        JsonNode days = body.get("days");
        assertThat(days).hasSize(3);
        String[] dates = {"2026-11-15", "2026-11-16", "2026-11-17"};
        double[] min = {6.0, 5.5, 4.0};
        double[] max = {12.5, 11.0, 9.5};
        int[] precipitation = {40, 20, 10};
        String[] conditions = {"RAIN", "PARTLY_CLOUDY", "FOG"};
        for (int i = 0; i < 3; i++) {
            JsonNode d = days.get(i);
            assertThat(d.get("date").asText()).as("day %d date", i).isEqualTo(dates[i]);
            assertThat(d.get("minTemperature").asDouble()).as("day %d min", i).isEqualTo(min[i]);
            assertThat(d.get("maxTemperature").asDouble()).as("day %d max", i).isEqualTo(max[i]);
            assertThat(d.get("precipitationProbability").asInt()).as("day %d precipitation", i)
                    .isEqualTo(precipitation[i]);
            assertThat(d.at("/condition/code").asText()).as("day %d condition", i).isEqualTo(conditions[i]);
            assertThat(d.at("/condition/description").asText()).isNotBlank();
        }
        new OpenApiSchemaAssert(json(get("/api/v1/openapi.json"))).assertConforms(body, "Forecast");

        RecordedRequest daily = received(Route.DAILY).get(0);
        assertThat(daily.param("start_date")).isEqualTo("2026-11-15");
        assertThat(daily.param("end_date")).isEqualTo("2026-11-17");
        assertThat(daily.param("timezone")).isEqualTo("UTC");
        assertThat(daily.param("temperature_unit")).isEqualTo("celsius");
        assertThat(daily.param("latitude")).isEqualTo("51.50853");
        assertThat(daily.param("longitude")).isEqualTo("-0.12574");
        assertThat(List.of(daily.param("daily").split(","))).containsExactlyInAnyOrder(
                "temperature_2m_min", "temperature_2m_max", "precipitation_probability_max", "weather_code");
        assertThat(received(Route.CURRENT)).isEmpty();
    }

    @Test
    @DisplayName("AC-8 (edge): days=1 and days=7 request exactly that window and return that many days")
    void ac8_edge_windowBounds() {
        for (int n : new int[] {1, 7}) {
            fake.reset();
            ResponseEntity<String> res = get("/api/v1/weather/forecast?city=London&days=" + n);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json(res).get("days")).hasSize(n);
            RecordedRequest daily = received(Route.DAILY).get(0);
            assertThat(daily.param("start_date")).isEqualTo("2026-11-15");
            assertThat(daily.param("end_date")).isEqualTo(TODAY.plusDays(n - 1L).toString());
        }
    }

    @Test
    @DisplayName("AC-19: fewer days than requested from the upstream gives 503 UPSTREAM_UNAVAILABLE")
    void ac19_shortDailyArrayIs503() {
        fake.stub(Route.DAILY, Response.ok(FakeOpenMeteo.json(FakeOpenMeteo.daily(TODAY, 2))));

        assertUpstreamUnavailable(get("/api/v1/weather/forecast?city=London&days=3"));
    }

    @Test
    @DisplayName("AC-19: daily dates not starting at the requested start_date give 503")
    void ac19_shiftedDatesAre503() {
        fake.stub(Route.DAILY, Response.ok(FakeOpenMeteo.json(FakeOpenMeteo.daily(TODAY.plusDays(1), 3))));

        assertUpstreamUnavailable(get("/api/v1/weather/forecast?city=London&days=3"));
    }

    @Test
    @DisplayName("AC-19 (edge): more days than requested also give 503 (never a longer days array)")
    void ac19_edge_longDailyArrayIs503() {
        fake.stub(Route.DAILY, Response.ok(FakeOpenMeteo.json(FakeOpenMeteo.daily(TODAY, 4))));

        assertUpstreamUnavailable(get("/api/v1/weather/forecast?city=London&days=3"));
    }

    @Test
    @DisplayName("AC-20: a null precipitation_probability_max gives 200 with precipitationProbability 0 for that day")
    void ac20_nullPrecipitationIsZero() {
        ObjectNode body = FakeOpenMeteo.daily(TODAY, 3);
        ((ArrayNode) body.at("/daily/precipitation_probability_max")).set(1, (JsonNode) null);
        fake.stub(Route.DAILY, Response.ok(FakeOpenMeteo.json(body)));

        ResponseEntity<String> res = get("/api/v1/weather/forecast?city=London&days=3");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode days = json(res).get("days");
        assertThat(days.get(0).get("precipitationProbability").asInt()).isEqualTo(40);
        assertThat(days.get(1).get("precipitationProbability").isIntegralNumber()).isTrue();
        assertThat(days.get(1).get("precipitationProbability").asInt()).isZero();
        assertThat(days.get(2).get("precipitationProbability").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("AC-20: a null min/max temperature or weather code in a day gives 503")
    void ac20_nullOtherDailyFieldIs503() {
        for (String field : new String[] {"temperature_2m_min", "temperature_2m_max", "weather_code"}) {
            fake.reset();
            ObjectNode body = FakeOpenMeteo.daily(TODAY, 3);
            ((ArrayNode) body.at("/daily/" + field)).set(2, (JsonNode) null);
            fake.stub(Route.DAILY, Response.ok(FakeOpenMeteo.json(body)));

            ResponseEntity<String> res = get("/api/v1/weather/forecast?city=London&days=3");
            assertThat(res.getStatusCode()).as("null %s", field).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(json(res).get("code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
        }
    }

    private static void assertUpstreamUnavailable(ResponseEntity<String> res) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode body = json(res);
        assertThat(body.get("code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(body.get("detail").asText()).isEqualTo("The weather provider is temporarily unavailable.");
        assertThat(body.has("days")).isFalse();
    }
}
