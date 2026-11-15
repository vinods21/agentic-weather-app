package com.example.weather.weather;

import com.example.weather.provider.WeatherProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Fetches weather from the {@link WeatherProvider} (always metric) and converts it to the
 * requested {@link Units}. Owns time: {@code observedAt} and the forecast start date (UTC).
 */
@Service
public class WeatherService {

    private final WeatherProvider provider;
    private final Clock clock;

    public WeatherService(WeatherProvider provider, Clock clock) {
        this.provider = provider;
        this.clock = clock;
    }

    public CurrentWeather current(String city, Units units) {
        CurrentConditions c = provider.current(city);
        Instant observedAt = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        return new CurrentWeather(
                c.location(),
                observedAt,
                units,
                temperature(c.temperature(), units),
                temperature(c.feelsLike(), units),
                c.humidity(),
                speed(c.windSpeed(), units),
                c.windDirection(),
                c.condition());
    }

    public Forecast forecast(String city, Units units, int days) {
        LocalDate start = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocationForecast f = provider.forecast(city, start, days);
        List<DailyForecast> converted = f.days().stream()
                .map(d -> new DailyForecast(
                        d.date(),
                        temperature(d.minTemperature(), units),
                        temperature(d.maxTemperature(), units),
                        d.precipitationProbability(),
                        d.condition()))
                .toList();
        return new Forecast(f.location(), units, converted);
    }

    private static double temperature(double celsius, Units units) {
        return units == Units.IMPERIAL ? UnitConverter.celsiusToFahrenheit(celsius) : celsius;
    }

    private static double speed(double metresPerSecond, Units units) {
        return units == Units.IMPERIAL ? UnitConverter.msToMph(metresPerSecond) : metresPerSecond;
    }
}
