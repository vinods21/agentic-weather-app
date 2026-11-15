package com.example.weather.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tester edge cases for AC-3/AC-4/AC-5 that the developer's parity table does not list: whitespace-only
 * and signed/zero-padded days, int-overflow boundaries, non-ASCII digits, and units with whitespace or
 * other case. {@link FavouritesForecastQuery} must report exactly the {@code (field, message)} set of
 * {@link ForecastQuery} (minus {@code city}) and resolve the same values. Plain {@link Validator}.
 */
class FavouritesForecastQueryEdgeParityTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    static Stream<Arguments> cases() {
        String[] units = {null, " metric", "metric ", "Metric", "metric,imperial", "metricimperial"};
        String[] days = {"   ", "\t", "-1", "-0", "+0", "+7", "007", "\t3\n", "1 2", "3,0", "1e1",
            "2147483647", "2147483648", "-2147483649", "٣", "３", "++3", "+-3", "3 days"};
        return Stream.concat(
                Stream.of(units).map(u -> Arguments.of(u, null)),
                Stream.of(days).map(d -> Arguments.of(null, d)));
    }

    private static Set<String> violations(Object query) {
        return VALIDATOR.validate(query).stream()
                .filter(v -> !v.getPropertyPath().toString().equals("city"))
                .map(v -> v.getPropertyPath() + "|" + v.getMessage())
                .collect(Collectors.toSet());
    }

    @ParameterizedTest(name = "units={0} days={1}")
    @MethodSource("cases")
    @DisplayName("AC-3/AC-4/AC-5 (edge): unusual units/days values give the same violations and resolved values as /weather/forecast")
    void edgeValuesMatchForecastQuery(String units, String days) {
        FavouritesForecastQuery favourites = new FavouritesForecastQuery(units, days);
        ForecastQuery forecast = new ForecastQuery("London", units, days);

        Set<String> actual = violations(favourites);
        assertThat(actual).isEqualTo(violations(forecast));
        if (actual.isEmpty()) {
            assertThat(favourites.resolvedUnits()).isEqualTo(forecast.resolvedUnits());
            assertThat(favourites.resolvedDays()).isEqualTo(forecast.resolvedDays());
        }
    }

    @Test
    @DisplayName("AC-3 (edge): whitespace-only days is not treated as absent; it is 'must be an integer between 1 and 7'")
    void whitespaceOnlyDaysIsInvalid() {
        assertThat(violations(new FavouritesForecastQuery(null, "   ")))
                .containsExactly("days|must be an integer between 1 and 7");
    }

    @Test
    @DisplayName("AC-3 (edge): +7, 007 and a tab/newline-wrapped 3 are valid and resolve to 7, 7 and 3")
    void signedAndPaddedDays() {
        assertThat(new FavouritesForecastQuery(null, "+7").resolvedDays()).isEqualTo(7);
        assertThat(new FavouritesForecastQuery(null, "007").resolvedDays()).isEqualTo(7);
        assertThat(new FavouritesForecastQuery(null, "\t3\n").resolvedDays()).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-5 (edge): non-ASCII digits and int overflow are 'must be an integer between 1 and 7', -0/+0 are 'must be between 1 and 7'")
    void nonAsciiAndOverflow() {
        for (String d : new String[] {"٣", "３", "2147483648", "-2147483649"}) {
            assertThat(violations(new FavouritesForecastQuery(null, d))).as(d)
                    .containsExactly("days|must be an integer between 1 and 7");
        }
        for (String d : new String[] {"-0", "+0", "2147483647"}) {
            assertThat(violations(new FavouritesForecastQuery(null, d))).as(d)
                    .containsExactly("days|must be between 1 and 7");
        }
    }
}
