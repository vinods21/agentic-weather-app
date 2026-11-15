package com.example.weather.api.v1;

import com.example.weather.weather.Units;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Query parameters of {@code GET /api/v1/weather/current}. Every parameter is bound as optional;
 * required-ness is a constraint so that all violations are reported together (ADR 0003).
 */
public record CurrentWeatherQuery(
        @NotBlank(message = "must not be blank")
        @Size(max = 100, message = "must be at most 100 characters")
        String city,
        @Pattern(regexp = "metric|imperial", message = "must be one of: metric, imperial")
        String units) {

    public CurrentWeatherQuery {
        city = city == null ? null : city.strip();
        units = units == null ? Units.METRIC.value() : units;
    }

    public Units resolvedUnits() {
        return Units.fromValue(units);
    }
}
