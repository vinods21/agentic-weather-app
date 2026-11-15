package com.example.weather.favourites;

/**
 * Why a favourite has no weather in {@code GET /api/v1/favourites/weather} (ADR 0014), or no forecast
 * in {@code GET /api/v1/favourites/forecast} (ADR 0016 §3). The constant names equal the matching
 * {@code ErrorCode} names.
 */
public enum FavouriteWeatherFailure {
    /** The provider failed, or the item did not finish within the request's time budget. */
    UPSTREAM_UNAVAILABLE,
    /** The provider has no data for the stored location. */
    LOCATION_NOT_FOUND
}
