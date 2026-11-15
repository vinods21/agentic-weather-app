package com.example.weather.api.v1;

import com.example.weather.weather.Units;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Query parameters of {@code GET /api/v1/weather/forecast}. See {@link CurrentWeatherQuery}. */
public record ForecastQuery(
        @NotBlank(message = "must not be blank")
        @Size(max = 100, message = "must be at most 100 characters")
        String city,
        @Pattern(regexp = "metric|imperial", message = "must be one of: metric, imperial")
        String units,
        @Min(value = 1, message = "must be between 1 and 7")
        @Max(value = 7, message = "must be between 1 and 7")
        Integer days) {

    public static final int DEFAULT_DAYS = 5;

    public ForecastQuery {
        city = city == null ? null : city.strip();
        units = units == null ? Units.METRIC.value() : units;
        days = days == null ? DEFAULT_DAYS : days;
    }

    public Units resolvedUnits() {
        return Units.fromValue(units);
    }
}
