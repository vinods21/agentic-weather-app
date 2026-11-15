package com.example.weather.api;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CORS allow-list for {@code /api/**} ({@code weather.api.cors.allowed-origins}, from the
 * comma-separated {@code WEATHER_API_CORS_ALLOWED_ORIGINS}). Empty means every origin is denied.
 */
@ConfigurationProperties(prefix = "weather.api.cors")
public record ApiCorsProperties(List<String> allowedOrigins) {

    public ApiCorsProperties {
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream()
                        .filter(o -> o != null && !o.isBlank())
                        .map(String::strip)
                        .toList();
    }
}
