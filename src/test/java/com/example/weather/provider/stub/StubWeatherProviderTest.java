package com.example.weather.provider.stub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.weather.provider.LocationNotFoundException;
import com.example.weather.weather.CurrentConditions;
import com.example.weather.weather.DailyForecast;
import com.example.weather.weather.Location;
import com.example.weather.weather.LocationForecast;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit test: no Spring context, no Docker. */
class StubWeatherProviderTest {

    private final StubWeatherProvider provider = new StubWeatherProvider();
    private static final LocalDate START = LocalDate.of(2026, 11, 15);

    @Test
    void returnsLondonCurrentConditions() {
        CurrentConditions c = provider.current("London");
        assertThat(c.location().name()).isEqualTo("London");
        assertThat(c.location().country()).isEqualTo("GB");
        assertThat(c.location().latitude()).isEqualTo(51.5074);
        assertThat(c.location().longitude()).isEqualTo(-0.1278);
        assertThat(c.humidity()).isBetween(0, 100);
        assertThat(c.windDirection()).isBetween(0, 359);
        assertThat(c.condition().code()).isNotNull();
    }

    @Test
    void isDeterministic() {
        assertThat(provider.current("London")).isEqualTo(provider.current("London"));
        assertThat(provider.forecast("Paris", START, 7)).isEqualTo(provider.forecast("Paris", START, 7));
    }

    @Test
    void lookupIsCaseInsensitiveAndReturnsCanonicalName() {
        assertThat(provider.current("lONDon").location().name()).isEqualTo("London");
        assertThat(provider.current("new york").location().name()).isEqualTo("New York");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Atlantis", "", "Londonx"})
    void unknownCityThrows(String city) {
        assertThatThrownBy(() -> provider.current(city))
                .isInstanceOf(LocationNotFoundException.class)
                .extracting(e -> ((LocationNotFoundException) e).city())
                .isEqualTo(city);
        assertThatThrownBy(() -> provider.forecast(city, START, 3)).isInstanceOf(LocationNotFoundException.class);
    }

    @Test
    void nullCityThrowsNotFound() {
        assertThatThrownBy(() -> provider.current(null)).isInstanceOf(LocationNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"London", "Paris", "New York", "Tokyo", "Sydney"})
    void forecastHasRequestedLengthConsecutiveDatesAndValidRanges(String city) {
        for (int days = 1; days <= StubWeatherProvider.MAX_DAYS; days++) {
            LocationForecast f = provider.forecast(city, START, days);
            assertThat(f.location().name()).isEqualTo(city);
            List<DailyForecast> list = f.days();
            assertThat(list).hasSize(days);
            for (int i = 0; i < days; i++) {
                DailyForecast d = list.get(i);
                assertThat(d.date()).isEqualTo(START.plusDays(i));
                assertThat(d.minTemperature()).isLessThanOrEqualTo(d.maxTemperature());
                assertThat(d.precipitationProbability()).isBetween(0, 100);
                assertThat(d.condition().code()).isNotNull();
            }
        }
    }

    @Test
    void forecastRejectsOutOfRangeDays() {
        assertThatThrownBy(() -> provider.forecast("London", START, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.forecast("London", START, 8)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void locateReturnsTheFixedLocationCaseInsensitively() {
        assertThat(provider.locate("  london ")).isEqualTo(new Location("London", "GB", 51.5074, -0.1278));
        assertThat(provider.locate("NEW YORK")).isEqualTo(provider.current("New York").location());
    }

    @Test
    void locateRejectsUnknownCities() {
        assertThatThrownBy(() -> provider.locate("Atlantis"))
                .isInstanceOfSatisfying(LocationNotFoundException.class, e -> assertThat(e.city()).isEqualTo("Atlantis"));
    }
}
