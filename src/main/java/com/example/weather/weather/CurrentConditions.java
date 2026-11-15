package com.example.weather.weather;

/** Current conditions as returned by a provider, always metric (deg C, m/s). */
public record CurrentConditions(
        Location location,
        double temperature,
        double feelsLike,
        int humidity,
        double windSpeed,
        int windDirection,
        Condition condition) {}
