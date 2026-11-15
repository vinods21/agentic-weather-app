package com.example.weather.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

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

/** Unit test: Bean Validation constraints on the query records, plain Hibernate Validator, no Spring. */
class QueryValidationTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    private static Set<String> messages(Object query) {
        return VALIDATOR.validate(query).stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }

    private static Set<String> invalidFields(Object query) {
        Set<ConstraintViolation<Object>> violations = VALIDATOR.validate(query);
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("AC-9 (unit): city of exactly 100 characters is valid, 101 is not")
    void cityLengthIsInclusive() {
        assertThat(invalidFields(new CurrentWeatherQuery("a".repeat(100), null))).isEmpty();
        assertThat(invalidFields(new CurrentWeatherQuery("a".repeat(101), null))).containsExactly("city");
    }

    @Test
    @DisplayName("AC-4/AC-9 (unit): length is measured after stripping surrounding whitespace")
    void lengthAfterStrip() {
        assertThat(invalidFields(new CurrentWeatherQuery("   " + "a".repeat(100) + "\t ", null))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", " "})
    @DisplayName("AC-7 (unit): empty or whitespace-only city is invalid (incl. Unicode em space)")
    void blankCityInvalid(String city) {
        assertThat(invalidFields(new CurrentWeatherQuery(city, null))).containsExactly("city");
        assertThat(invalidFields(new ForecastQuery(city, null, null))).containsExactly("city");
    }

    @Test
    @DisplayName("AC-7 (unit): missing city is invalid")
    void nullCityInvalid() {
        assertThat(invalidFields(new CurrentWeatherQuery(null, null))).containsExactly("city");
    }

    @ParameterizedTest
    @ValueSource(strings = {"METRIC", "Imperial", "kelvin", "standard", "", " metric", "metric ", "metric|imperial"})
    @DisplayName("AC-7 (unit): units other than exactly metric/imperial are invalid")
    void invalidUnits(String units) {
        assertThat(invalidFields(new CurrentWeatherQuery("London", units))).containsExactly("units");
        assertThat(invalidFields(new ForecastQuery("London", units, "3"))).containsExactly("units");
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 8, 99, Integer.MAX_VALUE})
    @DisplayName("AC-7 (unit): days outside 1..7 is invalid")
    void invalidDays(int days) {
        assertThat(invalidFields(new ForecastQuery("London", null, String.valueOf(days)))).containsExactly("days");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7})
    @DisplayName("AC-5 (unit): days 1..7 is valid")
    void validDays(int days) {
        assertThat(invalidFields(new ForecastQuery("London", "imperial", String.valueOf(days)))).isEmpty();
    }

    @Test
    @DisplayName("AC-8 (unit): every violation is collected, not only the first")
    void allViolationsCollected() {
        assertThat(invalidFields(new ForecastQuery(null, "kelvin", "99"))).containsExactlyInAnyOrder("city", "units", "days");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "3x", "0x3", "99999999999", " ", "1 2"})
    @DisplayName("AC-7 (unit): non-integer days is invalid with the fixed message")
    void nonIntegerDaysInvalid(String days) {
        ForecastQuery q = new ForecastQuery("London", null, days);
        assertThat(invalidFields(q)).containsExactly("days");
        assertThat(messages(q)).containsExactly("must be an integer between 1 and 7");
    }

    @Test
    @DisplayName("AC-7 (unit): out-of-range days uses the range message")
    void outOfRangeDaysMessage() {
        assertThat(messages(new ForecastQuery("London", null, "8"))).containsExactly("must be between 1 and 7");
    }

    @Test
    @DisplayName("AC-8 (unit): a non-integer days is reported together with the other violations (D-1)")
    void nonIntegerDaysCollectedWithOthers() {
        assertThat(invalidFields(new ForecastQuery(null, "kelvin", "abc")))
                .containsExactlyInAnyOrder("city", "units", "days");
    }
}
