package com.example.weather.api.v1.dto;

import com.example.weather.favourites.FavouriteWeather;
import java.util.List;

/** 200 body of {@code GET /api/v1/favourites/weather}: one item per favourite, in stored order. */
public record FavouriteWeatherListResponse(List<FavouriteWeatherResponse> items) {

    public static FavouriteWeatherListResponse from(List<FavouriteWeather> items) {
        return new FavouriteWeatherListResponse(items.stream().map(FavouriteWeatherResponse::from).toList());
    }
}
