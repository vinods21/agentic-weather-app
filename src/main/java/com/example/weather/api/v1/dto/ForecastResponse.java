package com.example.weather.api.v1.dto;

import com.example.weather.weather.Forecast;
import com.example.weather.weather.Units;
import java.util.List;

/** 200 body of {@code GET /api/v1/weather/forecast}. */
public record ForecastResponse(LocationDto location, Units units, List<ForecastDayDto> days) {

    public static ForecastResponse from(Forecast f) {
        return new ForecastResponse(
                LocationDto.from(f.location()), f.units(), f.days().stream().map(ForecastDayDto::from).toList());
    }
}
