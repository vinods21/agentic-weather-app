package com.example.weather.cache;

import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Weather cache switch and per-kind TTLs ({@code weather.cache.*}, ADR 0007 §6).
 *
 * <p>Binds alongside {@link CacheKeys} on the same prefix; each record binds only its own
 * components. A missing value takes its default; a zero or negative TTL fails startup with a
 * message naming the property and the env var (the value is not echoed).
 */
@ConfigurationProperties("weather.cache")
public record WeatherCacheProperties(
        Boolean enabled, Duration geocodingTtl, Duration notFoundTtl, Duration currentTtl, Duration forecastTtl) {

    static final Duration DEFAULT_GEOCODING_TTL = Duration.ofDays(7);
    static final Duration DEFAULT_NOT_FOUND_TTL = Duration.ofHours(1);
    static final Duration DEFAULT_CURRENT_TTL = Duration.ofMinutes(10);
    static final Duration DEFAULT_FORECAST_TTL = Duration.ofHours(1);

    public WeatherCacheProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        geocodingTtl = positive(geocodingTtl, DEFAULT_GEOCODING_TTL, "geocoding");
        notFoundTtl = positive(notFoundTtl, DEFAULT_NOT_FOUND_TTL, "not-found");
        currentTtl = positive(currentTtl, DEFAULT_CURRENT_TTL, "current");
        forecastTtl = positive(forecastTtl, DEFAULT_FORECAST_TTL, "forecast");
    }

    /** Defaults for every property (caching enabled, 7d / 1h / 10m / 1h). */
    public static WeatherCacheProperties defaults() {
        return new WeatherCacheProperties(null, null, null, null, null);
    }

    public boolean isEnabled() {
        return enabled;
    }

    private static Duration positive(Duration value, Duration fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value.isZero() || value.isNegative()) {
            String env = "WEATHER_CACHE_" + name.replace('-', '_').toUpperCase(Locale.ROOT) + "_TTL";
            throw new IllegalArgumentException(
                    "weather.cache." + name + "-ttl must be a positive duration (env " + env + ")");
        }
        return value;
    }
}
