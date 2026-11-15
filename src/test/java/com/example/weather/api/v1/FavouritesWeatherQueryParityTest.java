package com.example.weather.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tester-added AC-3 edge cases: for unusual {@code units} values, {@link FavouritesWeatherQuery}
 * reports exactly the same {@code units} violations as {@link CurrentWeatherQuery}. No Spring.
 */
class FavouritesWeatherQueryParityTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    private static <T> List<String> unitsViolations(T query) {
        return VALIDATOR.validate(query).stream()
                .filter(v -> v.getPropertyPath().toString().equals("units"))
                .map(ConstraintViolation::getMessage)
                .sorted()
                .toList();
    }

    @ParameterizedTest(name = "units=''{0}''")
    @ValueSource(strings = {" metric", "metric ", "Metric", "metric|imperial", "metric,imperial", "imperial\n",
            "metricimperial", " ", "metric", "imperial"})
    @DisplayName("AC-3 (edge): unusual units values give the same violations as /weather/current's query")
    void sameViolationsAsCurrentWeather(String units) {
        List<String> favourites = unitsViolations(new FavouritesWeatherQuery(units));
        List<String> current = unitsViolations(new CurrentWeatherQuery("London", units));

        assertThat(favourites).isEqualTo(current);
        if (!units.equals("metric") && !units.equals("imperial")) {
            assertThat(favourites).containsExactly("must be one of: metric, imperial");
        } else {
            assertThat(favourites).isEmpty();
        }
    }
}
