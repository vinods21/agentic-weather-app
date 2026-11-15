package com.example.weather.api.v1;

import com.example.weather.weather.Units;
import jakarta.validation.constraints.Pattern;

/**
 * Query parameters of {@code GET /api/v1/favourites/forecast}: the same {@code units} and {@code days}
 * rules, messages, defaults and parsing as {@link ForecastQuery}, without {@code city}. A missing
 * {@code units} means metric (an empty value is invalid); an empty {@code days} is treated as absent.
 */
public record FavouritesForecastQuery(
        @Pattern(regexp = "metric|imperial", message = "must be one of: metric, imperial")
        String units,
        @DaysParam
        String days) {

    public FavouritesForecastQuery {
        units = units == null ? Units.METRIC.value() : units;
        days = days == null || days.isEmpty() ? null : days;
    }

    public Units resolvedUnits() {
        return Units.fromValue(units);
    }

    /** The requested number of days, or {@link ForecastQuery#DEFAULT_DAYS} if absent. Only call on a validated query. */
    public int resolvedDays() {
        return days == null
                ? ForecastQuery.DEFAULT_DAYS
                : DaysParamValidator.parse(days).orElseThrow(() -> new IllegalStateException("days not validated"));
    }
}
