package com.example.weather.api.v1.dto;

import com.example.weather.favourites.Favourite;
import com.example.weather.favourites.FavouriteWeather;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One item of {@code GET /api/v1/favourites/weather}: the favourite's identity plus exactly one of
 * {@code weather} or {@code error} (ADR 0014). A missing label, weather or error is omitted.
 */
public record FavouriteWeatherResponse(
        String id,
        String city,
        @JsonInclude(JsonInclude.Include.NON_NULL) String label,
        LocationDto location,
        @JsonInclude(JsonInclude.Include.NON_NULL) CurrentWeatherResponse weather,
        @JsonInclude(JsonInclude.Include.NON_NULL) FavouriteWeatherErrorDto error) {

    public static FavouriteWeatherResponse from(FavouriteWeather item) {
        Favourite f = item.favourite();
        return new FavouriteWeatherResponse(
                f.id().toString(),
                f.city(),
                f.label(),
                LocationDto.from(f.location()),
                item.weather() == null ? null : CurrentWeatherResponse.from(item.weather()),
                item.failure() == null ? null : FavouriteWeatherErrorDto.from(item.failure()));
    }
}
