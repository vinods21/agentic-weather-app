package com.example.weather.api.v1.dto;

import com.example.weather.favourites.Favourite;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** A favourite as returned by every favourites endpoint. A missing label is omitted (ADR 0010 §3). */
public record FavouriteResponse(
        String id,
        String city,
        @JsonInclude(JsonInclude.Include.NON_NULL) String label,
        LocationDto location,
        Instant createdAt,
        Instant updatedAt) {

    public static FavouriteResponse from(Favourite f) {
        return new FavouriteResponse(f.id().toString(), f.city(), f.label(), LocationDto.from(f.location()),
                f.createdAt(), f.updatedAt());
    }
}
