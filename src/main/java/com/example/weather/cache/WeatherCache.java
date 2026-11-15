package com.example.weather.cache;

import com.example.weather.weather.CurrentConditions;
import com.example.weather.weather.DailyForecast;
import com.example.weather.weather.Location;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Cache of Open-Meteo results used by the adapter (ADR 0007). Every operation fails open: a
 * read that fails is an empty result, a write that fails is skipped; nothing here throws for a
 * Redis or decoding failure.
 */
public interface WeatherCache {

    /** Opens a session for one provider call. Sessions are not thread-safe and must not be shared. */
    Session session();

    /** A cache that never touches Redis: every read is a miss, every write a no-op. */
    static WeatherCache disabled() {
        return DisabledWeatherCache.INSTANCE;
    }

    /**
     * The cache operations of one provider call. After the first {@code unavailable} failure the
     * session skips its remaining operations (ADR 0007 §8).
     */
    interface Session {

        Optional<GeocodingEntry> geocoding(String city);

        void putLocation(String city, Location location);

        void putNotFound(String city);

        Optional<CurrentEntry> current(Location location);

        void putCurrent(Location location, CurrentConditions conditions, Instant fetchedAt);

        Optional<List<DailyForecast>> forecast(Location location, LocalDate startDate, int days);

        void putForecast(Location location, LocalDate startDate, int days, List<DailyForecast> forecastDays);
    }
}
