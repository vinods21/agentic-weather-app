package com.example.weather.api.v1.dto;

import com.example.weather.weather.CurrentWeather;
import com.example.weather.weather.Units;
import java.time.Instant;

/** 200 body of {@code GET /api/v1/weather/current}. Component order follows the contract. */
public record CurrentWeatherResponse(
        LocationDto location,
        Instant observedAt,
        Units units,
        double temperature,
        double feelsLike,
        int humidity,
        double windSpeed,
        int windDirection,
        ConditionDto condition) {

    public static CurrentWeatherResponse from(CurrentWeather w) {
        return new CurrentWeatherResponse(
                LocationDto.from(w.location()),
                w.observedAt(),
                w.units(),
                w.temperature(),
                w.feelsLike(),
                w.humidity(),
                w.windSpeed(),
                w.windDirection(),
                ConditionDto.from(w.condition()));
    }
}
