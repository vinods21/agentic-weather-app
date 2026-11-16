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
 * Unit test (AC-3/AC-4/AC-5): {@link FavouritesForecastQuery} reports exactly the same
 * {@code (field, message)} violations as {@link ForecastQuery} without its {@code city} entries,
 * and resolves the same units and days on valid input. Plain {@link Validator}, no Spring.
 */
class FavouritesForecastQueryParityTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    static Stream<Arguments> cases() {
        String[] units = {null, "metric", "imperial", "IMPERIAL", "kelvin", ""};
        String[] days = {null, "", "1", "3", "7", "0", "8", "abc", "3.5", "0x3", "99999999999", "+3", "03", " 3 ", "99"};
        return Stream.of(units).flatMap(u -> Stream.of(days).map(d -> Arguments.of(u, d)));
    }

    private static Set<String> violations(Object query) {
        return VALIDATOR.validate(query).stream()
                .filter(v -> !v.getPropertyPath().toString().equals("city"))
                .map(v -> v.getPropertyPath() + "|" + v.getMessage())
                .collect(Collectors.toSet());
    }

    @ParameterizedTest(name = "units={0} days={1}")
    @MethodSource("cases")
    @DisplayName("AC-3/AC-4/AC-5: the same violations as /weather/forecast, and the same resolved values when valid")
    void sameAsForecastQuery(String units, String days) {
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
    @DisplayName("AC-3: no days and an empty days give 5; +3, 03 and ' 3 ' give 3; no units gives metric")
    void defaultsAndNormalisation() {
        assertThat(new FavouritesForecastQuery(null, null).resolvedDays()).isEqualTo(ForecastQuery.DEFAULT_DAYS).isEqualTo(5);
        assertThat(new FavouritesForecastQuery(null, "").resolvedDays()).isEqualTo(5);
        assertThat(new FavouritesForecastQuery(null, "+3").resolvedDays()).isEqualTo(3);
        assertThat(new FavouritesForecastQuery(null, "03").resolvedDays()).isEqualTo(3);
        assertThat(new FavouritesForecastQuery(null, " 3 ").resolvedDays()).isEqualTo(3);
        assertThat(new FavouritesForecastQuery(null, null).resolvedUnits().value()).isEqualTo("metric");
    }

    @Test
    @DisplayName("AC-5: units=kelvin&days=99 reports both violations together")
    void bothReported() {
        assertThat(violations(new FavouritesForecastQuery("kelvin", "99"))).containsExactlyInAnyOrder(
                "units|must be one of: metric, imperial", "days|must be between 1 and 7");
    }
}
