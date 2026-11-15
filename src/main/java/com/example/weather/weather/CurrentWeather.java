package com.example.weather.weather;

import java.time.Instant;

/** Current weather already converted to {@link #units()}. */
public record CurrentWeather(
        Location location,
        Instant observedAt,
        Units units,
        double temperature,
        double feelsLike,
        int humidity,
        double windSpeed,
        int windDirection,
        Condition condition) {}
