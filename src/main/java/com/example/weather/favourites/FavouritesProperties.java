package com.example.weather.favourites;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Saved-favourites settings ({@code weather.favourites.*}). {@code max-per-client} defaults to
 * {@value #DEFAULT_MAX_PER_CLIENT}; a value outside {@value #MIN}..{@value #MAX} fails startup with
 * a message naming the property and the env var (the value is not echoed). After construction
 * {@link #maxPerClient()} is never null.
 */
@ConfigurationProperties("weather.favourites")
public record FavouritesProperties(Integer maxPerClient) {

    static final int DEFAULT_MAX_PER_CLIENT = 20;
    static final int MIN = 1;
    static final int MAX = 100;

    public FavouritesProperties {
        if (maxPerClient == null) {
            maxPerClient = DEFAULT_MAX_PER_CLIENT;
        } else if (maxPerClient < MIN || maxPerClient > MAX) {
            throw new IllegalArgumentException("weather.favourites.max-per-client must be between " + MIN + " and "
                    + MAX + " (env WEATHER_FAVOURITES_MAX_PER_CLIENT)");
        }
    }

    /** Defaults for every property. */
    public static FavouritesProperties defaults() {
        return new FavouritesProperties(null);
    }
}
