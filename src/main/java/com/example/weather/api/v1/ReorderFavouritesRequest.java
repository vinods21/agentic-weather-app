package com.example.weather.api.v1;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Body of {@code PUT /api/v1/favourites/order}: every favourite id of the client exactly once, in
 * the new order. Elements are strings so a non-UUID element is a field error on {@code ids}, not
 * an unreadable body.
 */
public record ReorderFavouritesRequest(
        @NotNull(message = "must not be null")
        @Size(max = 100, message = "must contain at most 100 ids")
        List<String> ids) {}
