package com.example.weather.provider.openmeteo;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.weather.Condition;
import com.example.weather.weather.ConditionCode;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit test: no Spring context, no Docker. */
class OpenMeteoWeatherCodesTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("AC-11: every listed weather code maps to its condition with a description")
    @CsvSource({
        "0, CLEAR",
        "1, PARTLY_CLOUDY", "2, PARTLY_CLOUDY",
        "3, CLOUDY",
        "45, FOG", "48, FOG",
        "51, DRIZZLE", "53, DRIZZLE", "55, DRIZZLE", "56, DRIZZLE", "57, DRIZZLE",
        "61, RAIN", "63, RAIN", "65, RAIN", "66, RAIN", "67, RAIN", "80, RAIN", "81, RAIN", "82, RAIN",
        "71, SNOW", "73, SNOW", "75, SNOW", "77, SNOW", "85, SNOW", "86, SNOW",
        "95, THUNDERSTORM", "96, THUNDERSTORM", "99, THUNDERSTORM"
    })
    void mapsCode(int code, ConditionCode expected) {
        Condition condition = OpenMeteoWeatherCodes.condition(code).orElseThrow();
        assertThat(condition.code()).isEqualTo(expected);
        assertThat(condition.description()).isNotBlank();
    }

    @Test
    @DisplayName("AC-11: exactly the 28 listed codes are mapped")
    void codesAreExactlyTheListedSet() {
        assertThat(OpenMeteoWeatherCodes.codes()).containsExactlyInAnyOrderElementsOf(Set.of(
                0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82,
                71, 73, 75, 77, 85, 86, 95, 96, 99));
    }

    @Test
    @DisplayName("AC-11: code 0 is described as Clear sky")
    void clearSky() {
        assertThat(OpenMeteoWeatherCodes.condition(0)).hasValue(new Condition(ConditionCode.CLEAR, "Clear sky"));
    }

    @ParameterizedTest
    @DisplayName("AC-11: unknown codes are not mapped")
    @ValueSource(ints = {4, 100, -1})
    void unknownCode(int code) {
        assertThat(OpenMeteoWeatherCodes.condition(code)).isEmpty();
    }
}
