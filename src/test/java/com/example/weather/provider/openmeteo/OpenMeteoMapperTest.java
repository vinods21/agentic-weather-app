package com.example.weather.provider.openmeteo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.weather.provider.LocationNotFoundException;
import com.example.weather.weather.ConditionCode;
import com.example.weather.weather.CurrentConditions;
import com.example.weather.weather.DailyForecast;
import com.example.weather.weather.Location;
import com.example.weather.weather.LocationForecast;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit test: no Spring context, no Docker. */
class OpenMeteoMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate START = LocalDate.of(2026, 11, 15);
    private static final Location LONDON = new Location("London", "GB", 51.50853, -0.12574);

    private final OpenMeteoMapper mapper = new OpenMeteoMapper();

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static JsonNode geocoding(String firstResult) {
        return json("{'results':[" + firstResult + "]}");
    }

    private static JsonNode current(String overrides) {
        String base = "'temperature_2m':11.2,'apparent_temperature':9.8,'relative_humidity_2m':81,"
                + "'wind_speed_10m':4.1,'wind_direction_10m':230,'weather_code':3";
        return json("{'current':{" + (overrides.isEmpty() ? base : overrides) + "}}");
    }

    private static String currentWith(String field, String value) {
        String fields = "'temperature_2m':11.2,'apparent_temperature':9.8,'relative_humidity_2m':81,"
                + "'wind_speed_10m':4.1,'wind_direction_10m':230,'weather_code':3";
        return fields.replaceAll("'" + field + "':[^,]*", "'" + field + "':" + value);
    }

    private static JsonNode daily(String time, String min, String max, String precipitation, String codes) {
        return json("{'daily':{'time':" + time + ",'temperature_2m_min':" + min + ",'temperature_2m_max':" + max
                + ",'precipitation_probability_max':" + precipitation + ",'weather_code':" + codes + "}}");
    }

    private static final String TIME3 = "['2026-11-15','2026-11-16','2026-11-17']";

    // --- geocoding ---------------------------------------------------------------------------

    @Test
    @DisplayName("AC-6: geocoding result maps to the location")
    void mapsLocation() {
        Location location = mapper.toLocation("London", geocoding(
                "{'name':'London','country_code':'GB','latitude':51.50853,'longitude':-0.12574}"));
        assertThat(location).isEqualTo(LONDON);
    }

    @Test
    @DisplayName("AC-12: country code is upper-cased")
    void upperCasesCountry() {
        Location location = mapper.toLocation("London", geocoding(
                "{'name':'London','country_code':'gb','latitude':51.5,'longitude':-0.1}"));
        assertThat(location.country()).isEqualTo("GB");
    }

    @ParameterizedTest
    @DisplayName("AC-13: no results means location not found")
    @ValueSource(strings = {"{}", "{'results':null}", "{'results':[]}", "{'results':{}}", "{'results':'x'}"})
    void noResults(String body) {
        assertThatThrownBy(() -> mapper.toLocation("Atlantis", json(body)))
                .isInstanceOfSatisfying(LocationNotFoundException.class,
                        e -> assertThat(e.city()).isEqualTo("Atlantis"));
    }

    @ParameterizedTest
    @DisplayName("AC-14: missing, blank or invalid country code means location not found")
    @ValueSource(strings = {
        "{'name':'X','latitude':1,'longitude':2}",
        "{'name':'X','country_code':null,'latitude':1,'longitude':2}",
        "{'name':'X','country_code':'','latitude':1,'longitude':2}",
        "{'name':'X','country_code':'  ','latitude':1,'longitude':2}",
        "{'name':'X','country_code':'GBR','latitude':1,'longitude':2}",
        "{'name':'X','country_code':'G1','latitude':1,'longitude':2}",
        "{'name':'X','country_code':7,'latitude':1,'longitude':2}"
    })
    void unusableCountry(String result) {
        assertThatThrownBy(() -> mapper.toLocation("X", geocoding(result)))
                .isInstanceOf(LocationNotFoundException.class);
    }

    @ParameterizedTest
    @DisplayName("AC-18: missing name or invalid coordinates are an invalid response")
    @ValueSource(strings = {
        "{'country_code':'GB','latitude':1,'longitude':2}",
        "{'name':' ','country_code':'GB','latitude':1,'longitude':2}",
        "{'name':'X','country_code':'GB','longitude':2}",
        "{'name':'X','country_code':'GB','latitude':'1','longitude':2}",
        "{'name':'X','country_code':'GB','latitude':91,'longitude':2}",
        "{'name':'X','country_code':'GB','latitude':1,'longitude':-181}",
        "{'name':'X','country_code':'GB','latitude':1,'longitude':null}",
        "'not an object'"
    })
    void invalidGeocoding(String result) {
        assertThatThrownBy(() -> mapper.toLocation("X", geocoding(result)))
                .isInstanceOf(InvalidUpstreamResponseException.class);
    }

    // --- current -----------------------------------------------------------------------------

    @Test
    @DisplayName("AC-6: current block maps to current conditions")
    void mapsCurrent() {
        CurrentConditions c = mapper.toCurrent(LONDON, current(""));
        assertThat(c.location()).isEqualTo(LONDON);
        assertThat(c.temperature()).isEqualTo(11.2);
        assertThat(c.feelsLike()).isEqualTo(9.8);
        assertThat(c.humidity()).isEqualTo(81);
        assertThat(c.windSpeed()).isEqualTo(4.1);
        assertThat(c.windDirection()).isEqualTo(230);
        assertThat(c.condition().code()).isEqualTo(ConditionCode.CLOUDY);
        assertThat(c.condition().description()).isNotBlank();
    }

    @ParameterizedTest(name = "humidity {0} -> {1}")
    @DisplayName("AC-12: humidity is rounded half-up and clamped to 0..100")
    @CsvSource({"80.6, 81", "80.5, 81", "80.4, 80", "101, 100", "-1, 0", "100, 100", "0, 0"})
    void humidity(String raw, int expected) {
        CurrentConditions c = mapper.toCurrent(LONDON, current(currentWith("relative_humidity_2m", raw)));
        assertThat(c.humidity()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "direction {0} -> {1}")
    @DisplayName("AC-12: wind direction is rounded and taken mod 360")
    @CsvSource({"360, 0", "359.6, 0", "229.5, 230", "0, 0", "-10, 350", "720.2, 0"})
    void direction(String raw, int expected) {
        CurrentConditions c = mapper.toCurrent(LONDON, current(currentWith("wind_direction_10m", raw)));
        assertThat(c.windDirection()).isEqualTo(expected);
    }

    @ParameterizedTest
    @DisplayName("AC-18: missing, null or non-numeric current fields are an invalid response")
    @CsvSource(delimiter = '|', value = {
        "temperature_2m|null", "temperature_2m|\"11.2\"", "apparent_temperature|null",
        "relative_humidity_2m|null", "wind_speed_10m|null", "wind_speed_10m|-0.1",
        "wind_direction_10m|true", "weather_code|null", "weather_code|4", "weather_code|3.5"
    })
    void invalidCurrentField(String field, String value) {
        assertThatThrownBy(() -> mapper.toCurrent(LONDON, current(currentWith(field, value))))
                .isInstanceOf(InvalidUpstreamResponseException.class);
    }

    @Test
    @DisplayName("AC-18: a missing current field or block is an invalid response")
    void missingCurrent() {
        assertThatThrownBy(() -> mapper.toCurrent(LONDON, current("'apparent_temperature':9.8,"
                + "'relative_humidity_2m':81,'wind_speed_10m':4.1,'wind_direction_10m':230,'weather_code':3")))
                .isInstanceOfSatisfying(InvalidUpstreamResponseException.class,
                        e -> assertThat(e.reason()).isEqualTo("missing current.temperature_2m"));
        assertThatThrownBy(() -> mapper.toCurrent(LONDON, json("{}")))
                .isInstanceOf(InvalidUpstreamResponseException.class);
    }

    @Test
    @DisplayName("AC-18: an unmapped weather code is an invalid response")
    void unmappedCode() {
        assertThatThrownBy(() -> mapper.toCurrent(LONDON, current(currentWith("weather_code", "4"))))
                .isInstanceOfSatisfying(InvalidUpstreamResponseException.class,
                        e -> assertThat(e.reason()).isEqualTo("unmapped weather_code"));
    }

    // --- daily -------------------------------------------------------------------------------

    @Test
    @DisplayName("AC-8: daily block maps to days in order")
    void mapsDaily() {
        LocationForecast f = mapper.toDaily(LONDON, daily(TIME3, "[6.0,5.5,4.0]", "[12.5,11.0,9.5]",
                "[40,20,10]", "[61,2,45]"), START, 3);
        assertThat(f.location()).isEqualTo(LONDON);
        assertThat(f.days()).extracting(DailyForecast::date)
                .containsExactly(START, START.plusDays(1), START.plusDays(2));
        assertThat(f.days()).extracting(DailyForecast::minTemperature).containsExactly(6.0, 5.5, 4.0);
        assertThat(f.days()).extracting(DailyForecast::maxTemperature).containsExactly(12.5, 11.0, 9.5);
        assertThat(f.days()).extracting(DailyForecast::precipitationProbability).containsExactly(40, 20, 10);
        assertThat(f.days()).extracting(d -> d.condition().code())
                .containsExactly(ConditionCode.RAIN, ConditionCode.PARTLY_CLOUDY, ConditionCode.FOG);
    }

    @Test
    @DisplayName("AC-12: precipitation probability is rounded and clamped")
    void precipitation() {
        LocationForecast f = mapper.toDaily(LONDON, daily(TIME3, "[1,1,1]", "[2,2,2]", "[37.5,120,-3]",
                "[0,0,0]"), START, 3);
        assertThat(f.days()).extracting(DailyForecast::precipitationProbability).containsExactly(38, 100, 0);
    }

    @Test
    @DisplayName("AC-20: null precipitation probability becomes 0")
    void nullPrecipitation() {
        LocationForecast f = mapper.toDaily(LONDON, daily(TIME3, "[1,1,1]", "[2,2,2]", "[null,20,10]",
                "[0,0,0]"), START, 3);
        assertThat(f.days()).extracting(DailyForecast::precipitationProbability).containsExactly(0, 20, 10);
    }

    @ParameterizedTest
    @DisplayName("AC-20: null in min/max temperature or weather code is an invalid response")
    @CsvSource(delimiter = '|', value = {
        "[null,1,1]|[2,2,2]|[0,0,0]",
        "[1,1,1]|[2,null,2]|[0,0,0]",
        "[1,1,1]|[2,2,2]|[0,0,null]",
        "[1,1,1]|[2,2,2]|[0,0,4]"
    })
    void nullDailyValue(String min, String max, String codes) {
        assertThatThrownBy(() -> mapper.toDaily(LONDON, daily(TIME3, min, max, "[1,1,1]", codes), START, 3))
                .isInstanceOf(InvalidUpstreamResponseException.class);
    }

    @Test
    @DisplayName("AC-19: fewer or more days than requested is an invalid response")
    void wrongLength() {
        assertThatThrownBy(() -> mapper.toDaily(LONDON, daily("['2026-11-15','2026-11-16']", "[1,1]", "[2,2]",
                "[0,0]", "[0,0]"), START, 3))
                .isInstanceOfSatisfying(InvalidUpstreamResponseException.class,
                        e -> assertThat(e.reason()).isEqualTo("daily length mismatch"));
        assertThatThrownBy(() -> mapper.toDaily(LONDON, daily(TIME3, "[1,1,1]", "[2,2,2]", "[0,0,0]", "[0,0,0]"),
                START, 2))
                .isInstanceOf(InvalidUpstreamResponseException.class);
    }

    @Test
    @DisplayName("AC-19: value arrays with a different length than time are an invalid response")
    void arrayLengthMismatch() {
        assertThatThrownBy(() -> mapper.toDaily(LONDON, daily(TIME3, "[1,1]", "[2,2,2]", "[0,0,0]", "[0,0,0]"),
                START, 3))
                .isInstanceOf(InvalidUpstreamResponseException.class);
    }

    @Test
    @DisplayName("AC-19: dates not starting at the requested start date are an invalid response")
    void shiftedDates() {
        assertThatThrownBy(() -> mapper.toDaily(LONDON, daily("['2026-11-16','2026-11-17','2026-11-18']",
                "[1,1,1]", "[2,2,2]", "[0,0,0]", "[0,0,0]"), START, 3))
                .isInstanceOfSatisfying(InvalidUpstreamResponseException.class,
                        e -> assertThat(e.reason()).isEqualTo("daily dates mismatch"));
    }

    @Test
    @DisplayName("AC-19: an unparsable date is an invalid response")
    void unparsableDate() {
        assertThatThrownBy(() -> mapper.toDaily(LONDON, daily("['2026-11-15','tomorrow','2026-11-17']",
                "[1,1,1]", "[2,2,2]", "[0,0,0]", "[0,0,0]"), START, 3))
                .isInstanceOf(InvalidUpstreamResponseException.class);
    }

    @Test
    @DisplayName("AC-18: a missing daily block or array is an invalid response")
    void missingDaily() {
        assertThatThrownBy(() -> mapper.toDaily(LONDON, json("{}"), START, 3))
                .isInstanceOf(InvalidUpstreamResponseException.class);
        assertThatThrownBy(() -> mapper.toDaily(LONDON, json("{'daily':{'time':" + TIME3 + "}}"), START, 3))
                .isInstanceOf(InvalidUpstreamResponseException.class);
    }
}
