package com.example.weather.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.weather.Units;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit test: Bean Validation on {@link FavouritesWeatherQuery}, plain Hibernate Validator, no Spring. */
class FavouritesWeatherQueryTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    private static Set<String> violations(Object query) {
        return VALIDATOR.validate(query).stream()
                .map((ConstraintViolation<Object> v) -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.toSet());
    }

    @ParameterizedTest
    @ValueSource(strings = {"IMPERIAL", "kelvin", ""})
    @DisplayName("AC-3: invalid units give the same violation as /weather/current")
    void sameViolationsAsCurrentWeather(String units) {
        Set<String> expected = violations(new CurrentWeatherQuery("London", units));
        assertThat(expected).containsExactly("units: must be one of: metric, imperial");
        assertThat(violations(new FavouritesWeatherQuery(units))).isEqualTo(expected);
    }

    @Test
    @DisplayName("AC-3: a missing units value means metric; metric and imperial are valid")
    void defaultsAndValidValues() {
        assertThat(new FavouritesWeatherQuery(null).resolvedUnits()).isEqualTo(Units.METRIC);
        assertThat(violations(new FavouritesWeatherQuery(null))).isEmpty();
        assertThat(new FavouritesWeatherQuery("metric").resolvedUnits()).isEqualTo(Units.METRIC);
        assertThat(new FavouritesWeatherQuery("imperial").resolvedUnits()).isEqualTo(Units.IMPERIAL);
        assertThat(violations(new FavouritesWeatherQuery("imperial"))).isEmpty();
    }
}
