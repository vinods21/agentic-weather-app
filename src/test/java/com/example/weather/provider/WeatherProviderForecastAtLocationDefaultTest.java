package com.example.weather.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.provider.stub.StubWeatherProvider;
import com.example.weather.weather.CurrentConditions;
import com.example.weather.weather.Location;
import com.example.weather.weather.LocationForecast;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit test: the default {@code forecastAtLocation} delegates to {@code forecast(name, start, days)} (ADR 0015 §1). */
class WeatherProviderForecastAtLocationDefaultTest {

    @Test
    @DisplayName("AC-17: the default forecastAtLocation resolves the stored canonical name and returns its result unchanged")
    void defaultUsesForecastWithName() {
        StubWeatherProvider stub = new StubWeatherProvider();
        List<String> calls = new ArrayList<>();
        List<LocationForecast> returned = new ArrayList<>();
        WeatherProvider onlyAbstractMethods = new WeatherProvider() {
            @Override
            public CurrentConditions current(String city) {
                return stub.current(city);
            }

            @Override
            public LocationForecast forecast(String city, LocalDate startDate, int days) {
                calls.add(city + "|" + startDate + "|" + days);
                LocationForecast result = stub.forecast(city, startDate, days);
                returned.add(result);
                return result;
            }
        };
        LocalDate start = LocalDate.of(2026, 11, 15);

        LocationForecast result =
                onlyAbstractMethods.forecastAtLocation(new Location("Paris", "FR", 1.0, 2.0), start, 3);

        assertThat(calls).containsExactly("Paris|2026-11-15|3");
        assertThat(result).isSameAs(returned.getFirst());
    }
}
