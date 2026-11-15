package com.example.weather.cache;

import com.example.weather.weather.CurrentConditions;
import com.example.weather.weather.DailyForecast;
import com.example.weather.weather.Location;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** {@link WeatherCache#disabled()}: no Redis access at all (AC-4). */
enum DisabledWeatherCache implements WeatherCache, WeatherCache.Session {
    INSTANCE;

    @Override
    public Session session() {
        return this;
    }

    @Override
    public Optional<GeocodingEntry> geocoding(String city) {
        return Optional.empty();
    }

    @Override
    public void putLocation(String city, Location location) {
        // no-op
    }

    @Override
    public void putNotFound(String city) {
        // no-op
    }

    @Override
    public Optional<CurrentEntry> current(Location location) {
        return Optional.empty();
    }

    @Override
    public void putCurrent(Location location, CurrentConditions conditions, Instant fetchedAt) {
        // no-op
    }

    @Override
    public Optional<List<DailyForecast>> forecast(Location location, LocalDate startDate, int days) {
        return Optional.empty();
    }

    @Override
    public void putForecast(Location location, LocalDate startDate, int days, List<DailyForecast> forecastDays) {
        // no-op
    }
}
