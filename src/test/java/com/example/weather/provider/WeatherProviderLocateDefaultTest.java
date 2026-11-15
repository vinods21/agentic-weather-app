package com.example.weather.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.provider.stub.StubWeatherProvider;
import com.example.weather.weather.CurrentConditions;
import com.example.weather.weather.LocationForecast;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Unit test: the default {@code locate} delegates to {@code current} (ADR 0011 §1). */
class WeatherProviderLocateDefaultTest {

    @Test
    void defaultLocateUsesCurrent() {
        StubWeatherProvider stub = new StubWeatherProvider();
        WeatherProvider onlyAbstractMethods = new WeatherProvider() {
            @Override
            public CurrentConditions current(String city) {
                return stub.current(city);
            }

            @Override
            public LocationForecast forecast(String city, LocalDate startDate, int days) {
                return stub.forecast(city, startDate, days);
            }
        };
        assertThat(onlyAbstractMethods.locate("Paris")).isEqualTo(stub.current("Paris").location());
    }
}
