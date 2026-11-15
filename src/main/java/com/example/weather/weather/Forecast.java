package com.example.weather.weather;

import java.util.List;

/** Daily forecast already converted to {@link #units()}, ordered by date ascending. */
public record Forecast(Location location, Units units, List<DailyForecast> days) {

    public Forecast {
        days = List.copyOf(days);
    }
}
