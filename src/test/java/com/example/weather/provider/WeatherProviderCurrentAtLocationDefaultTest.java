package com.example.weather.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.provider.stub.StubWeatherProvider;
import com.example.weather.weather.CurrentConditions;
import com.example.weather.weather.Location;
import com.example.weather.weather.LocationForecast;
import com.example.weather.weather.ObservedConditions;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit test: the default {@code currentAtLocation} delegates to {@code currentAt(name, now)} (ADR 0012 §1). */
class WeatherProviderCurrentAtLocationDefaultTest {

    @Test
    @DisplayName("AC-14: the default currentAtLocation resolves the stored canonical name")
    void defaultUsesCurrentAtWithName() {
        StubWeatherProvider stub = new StubWeatherProvider();
        List<String> cities = new ArrayList<>();
        WeatherProvider onlyAbstractMethods = new WeatherProvider() {
            @Override
            public CurrentConditions current(String city) {
                cities.add(city);
                return stub.current(city);
            }

            @Override
            public LocationForecast forecast(String city, LocalDate startDate, int days) {
                return stub.forecast(city, startDate, days);
            }
        };
        Instant now = Instant.parse("2026-11-15T10:00:00Z");

        ObservedConditions observed =
                onlyAbstractMethods.currentAtLocation(new Location("Paris", "FR", 1.0, 2.0), now);

        assertThat(cities).containsExactly("Paris");
        assertThat(observed).isEqualTo(new ObservedConditions(stub.current("Paris"), now));
    }
}
