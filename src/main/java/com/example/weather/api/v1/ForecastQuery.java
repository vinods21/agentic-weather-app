package com.example.weather.api.v1;

import com.example.weather.weather.Units;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Query parameters of {@code GET /api/v1/weather/forecast}. See {@link CurrentWeatherQuery}.
 *
 * <p>{@code days} is bound as the raw string and checked by {@link DaysParam}, so that a non-integer
 * value does not stop the other parameters from being validated. Use {@link #resolvedDays()}.
 */
public record ForecastQuery(
        @NotBlank(message = "must not be blank")
        @Size(max = 100, message = "must be at most 100 characters")
        String city,
        @Pattern(regexp = "metric|imperial", message = "must be one of: metric, imperial")
        String units,
        @DaysParam
        String days) {

    public static final int DEFAULT_DAYS = 5;

    public ForecastQuery {
        city = city == null ? null : city.strip();
        units = units == null ? Units.METRIC.value() : units;
        // An empty days= is treated as absent (documented in the OpenAPI spec).
        days = days == null || days.isEmpty() ? null : days;
    }

    public Units resolvedUnits() {
        return Units.fromValue(units);
    }

    /** The requested number of days, or {@link #DEFAULT_DAYS} if absent. Only call on a validated query. */
    public int resolvedDays() {
        return days == null
                ? DEFAULT_DAYS
                : DaysParamValidator.parse(days).orElseThrow(() -> new IllegalStateException("days not validated"));
    }
}
