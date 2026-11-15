package com.example.weather.api.v1.dto;

import com.example.weather.favourites.FavouriteForecast;
import java.util.List;

/** 200 body of {@code GET /api/v1/favourites/forecast}: one item per favourite, in stored order. */
public record FavouriteForecastListResponse(List<FavouriteForecastResponse> items) {

    public static FavouriteForecastListResponse from(List<FavouriteForecast> items) {
        return new FavouriteForecastListResponse(items.stream().map(FavouriteForecastResponse::from).toList());
    }
}
