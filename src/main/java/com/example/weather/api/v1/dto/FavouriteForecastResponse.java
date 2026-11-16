package com.example.weather.api.v1.dto;

import com.example.weather.favourites.Favourite;
import com.example.weather.favourites.FavouriteForecast;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One item of {@code GET /api/v1/favourites/forecast}: the favourite's identity plus exactly one of
 * {@code forecast} or {@code error} (ADR 0014, 0016 §3). A missing label, forecast or error is omitted.
 */
public record FavouriteForecastResponse(
        String id,
        String city,
        @JsonInclude(JsonInclude.Include.NON_NULL) String label,
        LocationDto location,
        @JsonInclude(JsonInclude.Include.NON_NULL) ForecastResponse forecast,
        @JsonInclude(JsonInclude.Include.NON_NULL) FavouriteWeatherErrorDto error) {

    public static FavouriteForecastResponse from(FavouriteForecast item) {
        Favourite f = item.favourite();
        return new FavouriteForecastResponse(
                f.id().toString(),
                f.city(),
                f.label(),
                LocationDto.from(f.location()),
                item.forecast() == null ? null : ForecastResponse.from(item.forecast()),
                item.failure() == null ? null : FavouriteWeatherErrorDto.from(item.failure()));
    }
}
