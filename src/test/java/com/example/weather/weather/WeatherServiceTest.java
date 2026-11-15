package com.example.weather.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.example.weather.provider.WeatherProvider;
import com.example.weather.provider.stub.StubWeatherProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit test: no Spring context, no Docker. */
class WeatherServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T23:30:45.678Z");

    private final WeatherService service =
            new WeatherService(new StubWeatherProvider(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void metricCurrentIsPassedThroughWithTruncatedObservedAt() {
        CurrentWeather w = service.current("London", Units.METRIC);
        assertThat(w.units()).isEqualTo(Units.METRIC);
        assertThat(w.location().name()).isEqualTo("London");
        assertThat(w.temperature()).isEqualTo(11.2);
        assertThat(w.windSpeed()).isEqualTo(4.1);
        assertThat(w.observedAt()).isEqualTo(Instant.parse("2026-11-15T23:30:45Z"));
    }

    @Test
    @DisplayName("AC-24: the service passes its truncated clock time and uses the provider's observedAt")
    void observedAtComesFromProvider() {
        Instant fetchedAt = Instant.parse("2026-11-15T23:25:00Z");
        Instant[] passed = new Instant[1];
        StubWeatherProvider stub = new StubWeatherProvider();
        WeatherProvider cached = new WeatherProvider() {
            @Override
            public CurrentConditions current(String city) {
                throw new AssertionError("currentAt must be used");
            }

            @Override
            public ObservedConditions currentAt(String city, Instant now) {
                passed[0] = now;
                return new ObservedConditions(stub.current(city), fetchedAt);
            }

            @Override
            public LocationForecast forecast(String city, LocalDate startDate, int days) {
                return stub.forecast(city, startDate, days);
            }
        };

        CurrentWeather w = new WeatherService(cached, Clock.fixed(NOW, ZoneOffset.UTC)).current("London", Units.IMPERIAL);

        assertThat(passed[0]).isEqualTo(Instant.parse("2026-11-15T23:30:45Z"));
        assertThat(w.observedAt()).isEqualTo(fetchedAt);
        assertThat(w.units()).isEqualTo(Units.IMPERIAL);
    }

    @Test
    void imperialCurrentConvertsTemperatureAndSpeed() {
        CurrentWeather metric = service.current("London", Units.METRIC);
        CurrentWeather imperial = service.current("London", Units.IMPERIAL);
        assertThat(imperial.units()).isEqualTo(Units.IMPERIAL);
        assertThat(imperial.temperature()).isCloseTo(metric.temperature() * 9 / 5 + 32, within(0.1));
        assertThat(imperial.feelsLike()).isCloseTo(metric.feelsLike() * 9 / 5 + 32, within(0.1));
        assertThat(imperial.windSpeed()).isCloseTo(metric.windSpeed() * 2.2369363, within(0.1));
        assertThat(imperial.humidity()).isEqualTo(metric.humidity());
        assertThat(imperial.windDirection()).isEqualTo(metric.windDirection());
    }

    @Test
    void forecastStartsAtTodaysUtcDateEvenWithNonUtcClockZone() {
        WeatherService tokyoClock =
                new WeatherService(new StubWeatherProvider(), Clock.fixed(NOW, ZoneId.of("Asia/Tokyo")));
        Forecast f = tokyoClock.forecast("London", Units.METRIC, 3);
        assertThat(f.days()).hasSize(3);
        assertThat(f.days().get(0).date()).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(f.days().get(2).date()).isEqualTo(LocalDate.of(2026, 11, 17));
    }

    @Test
    void imperialForecastConvertsTemperatures() {
        Forecast metric = service.forecast("London", Units.METRIC, 5);
        Forecast imperial = service.forecast("London", Units.IMPERIAL, 5);
        assertThat(imperial.units()).isEqualTo(Units.IMPERIAL);
        for (int i = 0; i < 5; i++) {
            DailyForecast m = metric.days().get(i);
            DailyForecast f = imperial.days().get(i);
            assertThat(f.date()).isEqualTo(m.date());
            assertThat(f.minTemperature()).isCloseTo(m.minTemperature() * 9 / 5 + 32, within(0.1));
            assertThat(f.maxTemperature()).isCloseTo(m.maxTemperature() * 9 / 5 + 32, within(0.1));
            assertThat(f.minTemperature()).isLessThanOrEqualTo(f.maxTemperature());
            assertThat(f.precipitationProbability()).isEqualTo(m.precipitationProbability());
        }
    }
}
