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
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        return convert(provider.currentAt(city, now), units);
    }

    /**
     * Current weather at a stored location, without geocoding (ADR 0012). {@code now} is the
     * caller's service-clock instant truncated to seconds, so one request can share it across items.
     */
    public CurrentWeather currentAt(Location location, Units units, Instant now) {
        return convert(provider.currentAtLocation(location, now), units);
    }

    private static CurrentWeather convert(ObservedConditions observed, Units units) {
        CurrentConditions c = observed.conditions();
        return new CurrentWeather(
                c.location(),
                observed.observedAt(),
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
        return convert(provider.forecast(city, start, days), units);
    }

    /**
     * Daily forecast at a stored location, without geocoding (ADR 0015). {@code startDate} is the
     * caller's UTC date from the service clock, so one request can share it across items; this
     * method never reads the clock.
     */
    public Forecast forecastAt(Location location, Units units, LocalDate startDate, int days) {
        return convert(provider.forecastAtLocation(location, startDate, days), units);
    }

    private static Forecast convert(LocationForecast f, Units units) {
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
