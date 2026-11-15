package com.example.weather.api.v1.dto;

import com.example.weather.favourites.Favourite;
import java.util.List;

/** 200 body of the list and reorder endpoints: the client's favourites in list order. */
public record FavouriteListResponse(List<FavouriteResponse> items) {

    public static FavouriteListResponse from(List<Favourite> favourites) {
        return new FavouriteListResponse(favourites.stream().map(FavouriteResponse::from).toList());
    }
}
