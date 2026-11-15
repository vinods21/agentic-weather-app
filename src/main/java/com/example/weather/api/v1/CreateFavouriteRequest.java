package com.example.weather.api.v1;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/favourites}. Both values are stripped before validation; a missing
 * or null label means "no label". The city rules match the weather endpoints' {@code city}.
 */
public record CreateFavouriteRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 100, message = "must be at most 100 characters")
        String city,
        @Size(min = 1, message = "must not be blank")
        @Size(max = 50, message = "must be at most 50 characters")
        String label) {

    public CreateFavouriteRequest {
        city = city == null ? null : city.strip();
        label = label == null ? null : label.strip();
    }
}
