package com.example.weather.api.v1.dto;

import com.example.weather.api.error.ApiExceptionHandler;
import com.example.weather.api.error.ErrorCode;
import com.example.weather.favourites.FavouriteWeatherFailure;

/**
 * Per-item error of {@code GET /api/v1/favourites/weather} and {@code GET /api/v1/favourites/forecast}
 * (ADR 0014 §4, ADR 0016 §3): an {@link ErrorCode} name
 * and a fixed detail text that never contains exception messages or user input.
 */
public record FavouriteWeatherErrorDto(String code, String detail) {

    public static final String LOCATION_NOT_FOUND_DETAIL = "No weather data is available for this location.";

    public static FavouriteWeatherErrorDto from(FavouriteWeatherFailure failure) {
        String code = ErrorCode.valueOf(failure.name()).name();
        String detail = switch (failure) {
            case UPSTREAM_UNAVAILABLE -> ApiExceptionHandler.UPSTREAM_DETAIL;
            case LOCATION_NOT_FOUND -> LOCATION_NOT_FOUND_DETAIL;
        };
        return new FavouriteWeatherErrorDto(code, detail);
    }
}
