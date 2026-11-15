package com.example.weather.api.v1;

import com.example.weather.weather.Units;
import jakarta.validation.constraints.Pattern;

/**
 * Query parameters of {@code GET /api/v1/favourites/weather}: the same {@code units} rule and
 * message as {@link CurrentWeatherQuery}. A missing value means metric; an empty value is invalid.
 */
public record FavouritesWeatherQuery(
        @Pattern(regexp = "metric|imperial", message = "must be one of: metric, imperial")
        String units) {

    public FavouritesWeatherQuery {
        units = units == null ? Units.METRIC.value() : units;
    }

    public Units resolvedUnits() {
        return Units.fromValue(units);
    }
}
