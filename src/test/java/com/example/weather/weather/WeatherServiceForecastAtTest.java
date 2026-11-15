package com.example.weather.weather;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.provider.WeatherProvider;
import com.example.weather.provider.stub.StubWeatherProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit test of {@link WeatherService#forecastAt} (ADR 0015): no Spring context, no Docker. */
class WeatherServiceForecastAtTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 15);

    /** A clock that fails the test when it is read. */
    private static final Clock NEVER_READ = new Clock() {
        @Override
        public ZoneId getZone() {
            throw new AssertionError("forecastAt must not read the clock");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new AssertionError("forecastAt must not read the clock");
        }

        @Override
        public Instant instant() {
            throw new AssertionError("forecastAt must not read the clock");
        }
    };

    private final StubWeatherProvider stub = new StubWeatherProvider();
    private final List<String> calls = new ArrayList<>();
    private final WeatherProvider recording = new WeatherProvider() {
        @Override
        public CurrentConditions current(String city) {
            throw new AssertionError("not used");
        }

        @Override
        public LocationForecast forecast(String city, LocalDate startDate, int days) {
            throw new AssertionError("forecastAtLocation must be used");
        }

        @Override
        public LocationForecast forecastAtLocation(Location location, LocalDate startDate, int days) {
            calls.add(location.name() + "|" + startDate + "|" + days);
            return stub.forecastAtLocation(location, startDate, days);
        }
    };

    @Test
    @DisplayName("AC-1: forecastAt passes the location, start date and days through, metric unchanged, without reading the clock")
    void metricPassedThrough() {
        Location london = stub.locate("London");

        Forecast forecast = new WeatherService(recording, NEVER_READ).forecastAt(london, Units.METRIC, START, 3);

        assertThat(calls).containsExactly("London|2026-11-15|3");
        assertThat(forecast.units()).isEqualTo(Units.METRIC);
        assertThat(forecast.location()).isEqualTo(london);
        assertThat(forecast.days()).isEqualTo(stub.forecast("London", START, 3).days());
    }

    @Test
    @DisplayName("AC-4: forecastAt converts like forecast(city, units, days)")
    void convertsLikeForecastByCity() {
        Location paris = stub.locate("Paris");
        WeatherService byCity = new WeatherService(stub, Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC));

        for (Units units : Units.values()) {
            Forecast at = new WeatherService(recording, NEVER_READ).forecastAt(paris, units, START, 5);
            assertThat(at).isEqualTo(byCity.forecast("Paris", units, 5));
        }
        Forecast imperial = new WeatherService(recording, NEVER_READ).forecastAt(paris, Units.IMPERIAL, START, 1);
        assertThat(imperial.units()).isEqualTo(Units.IMPERIAL);
        assertThat(imperial.days().getFirst().maxTemperature())
                .isEqualTo(UnitConverter.celsiusToFahrenheit(stub.forecast("Paris", START, 1).days().getFirst().maxTemperature()));
    }
}
