package com.example.weather.favourites;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of {@code GET /api/v1/favourites/weather} ({@code weather.favourites.weather.*}, ADR 0013).
 * {@code max-concurrency} (default {@value #DEFAULT_MAX_CONCURRENCY}, {@value #MIN_CONCURRENCY}..
 * {@value #MAX_CONCURRENCY}) bounds the upstream calls in flight per request; {@code timeout}
 * (default 5s, 1s..30s) is the overall time budget of a request. A value out of range fails startup
 * with a message naming the property and the env var (the value is not echoed). After construction
 * neither accessor returns null.
 */
@ConfigurationProperties("weather.favourites.weather")
public record FavouritesWeatherProperties(Integer maxConcurrency, Duration timeout) {

    static final int DEFAULT_MAX_CONCURRENCY = 4;
    static final int MIN_CONCURRENCY = 1;
    static final int MAX_CONCURRENCY = 20;
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration MIN_TIMEOUT = Duration.ofSeconds(1);
    static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);

    public FavouritesWeatherProperties {
        if (maxConcurrency == null) {
            maxConcurrency = DEFAULT_MAX_CONCURRENCY;
        } else if (maxConcurrency < MIN_CONCURRENCY || maxConcurrency > MAX_CONCURRENCY) {
            throw new IllegalArgumentException("weather.favourites.weather.max-concurrency must be between "
                    + MIN_CONCURRENCY + " and " + MAX_CONCURRENCY + " (env WEATHER_FAVOURITES_WEATHER_MAX_CONCURRENCY)");
        }
        if (timeout == null) {
            timeout = DEFAULT_TIMEOUT;
        } else if (timeout.compareTo(MIN_TIMEOUT) < 0 || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("weather.favourites.weather.timeout must be between 1s and 30s"
                    + " (env WEATHER_FAVOURITES_WEATHER_TIMEOUT)");
        }
    }

    /** Defaults for every property. */
    public static FavouritesWeatherProperties defaults() {
        return new FavouritesWeatherProperties(null, null);
    }
}
