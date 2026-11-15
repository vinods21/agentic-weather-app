package com.example.weather.weather;

import java.util.List;

/** A provider's forecast result: the resolved location plus metric daily forecasts. */
public record LocationForecast(Location location, List<DailyForecast> days) {

    public LocationForecast {
        days = List.copyOf(days);
    }
}
