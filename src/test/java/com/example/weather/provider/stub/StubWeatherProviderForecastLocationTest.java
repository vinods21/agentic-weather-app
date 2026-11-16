package com.example.weather.provider.stub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.weather.provider.LocationNotFoundException;
import com.example.weather.weather.Location;
import com.example.weather.weather.LocationForecast;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit test: {@link StubWeatherProvider#forecastAtLocation} matches exact coordinates (ADR 0015 §4). */
class StubWeatherProviderForecastLocationTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 15);

    private final StubWeatherProvider stub = new StubWeatherProvider();

    static Stream<Arguments> citiesAndDays() {
        return Stream.of("London", "Paris", "New York", "Tokyo", "Sydney")
                .flatMap(city -> Stream.of(1, 5, 7).map(n -> Arguments.of(city, n)));
    }

    @ParameterizedTest
    @MethodSource("citiesAndDays")
    @DisplayName("AC-1/AC-3: each fixed location gives the city's first n days, with the passed-in location")
    void knownLocations(String city, int days) {
        Location location = stub.locate(city);

        LocationForecast forecast = stub.forecastAtLocation(location, START, days);

        assertThat(forecast.days()).isEqualTo(stub.forecast(city, START, days).days());
        assertThat(forecast.days()).hasSize(days);
        assertThat(forecast.days().getFirst().date()).isEqualTo(START);
        assertThat(forecast.location()).isSameAs(location);
    }

    @Test
    @DisplayName("AC-1: name and country are ignored for matching, and the passed-in location is returned")
    void nameIgnored() {
        Location paris = stub.locate("Paris");
        Location renamed = new Location("Somewhere", "XX", paris.latitude(), paris.longitude());

        LocationForecast forecast = stub.forecastAtLocation(renamed, START, 3);

        assertThat(forecast.days()).isEqualTo(stub.forecast("Paris", START, 3).days());
        assertThat(forecast.location()).isEqualTo(renamed);
    }

    @Test
    @DisplayName("AC-17: a known name with other coordinates, or unknown coordinates, is not found")
    void unknownCoordinates() {
        Location london = stub.locate("London");
        assertThatThrownBy(() -> stub.forecastAtLocation(
                new Location("London", "GB", london.latitude() + 0.0001, london.longitude()), START, 3))
                .isInstanceOf(LocationNotFoundException.class);
        assertThatThrownBy(() -> stub.forecastAtLocation(new Location("Nowhere", "XX", 0.0, 0.0), START, 3))
                .isInstanceOf(LocationNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 8})
    @DisplayName("AC-3: days outside 1..7 is a bug (IllegalArgumentException)")
    void daysOutOfRange(int days) {
        Location london = stub.locate("London");
        assertThatThrownBy(() -> stub.forecastAtLocation(london, START, days))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
