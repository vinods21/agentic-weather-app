package com.example.weather.cache;

import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Builds namespaced Redis keys ({@code <prefix>:<part>:<part>}).
 *
 * <p>The prefix comes from {@code weather.cache.key-prefix}. Integration tests set a unique
 * prefix per run so parallel or repeated runs never see each other's keys.
 */
@ConfigurationProperties(prefix = "weather.cache")
public record CacheKeys(String keyPrefix) {

    public CacheKeys {
        keyPrefix = (keyPrefix == null || keyPrefix.isBlank()) ? "weather" : stripTrailingColons(keyPrefix.strip());
    }

    public String key(String... parts) {
        Objects.requireNonNull(parts, "parts");
        if (parts.length == 0) {
            throw new IllegalArgumentException("at least one key part is required");
        }
        StringBuilder sb = new StringBuilder(keyPrefix);
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                throw new IllegalArgumentException("key parts must not be blank");
            }
            sb.append(':').append(part.strip());
        }
        return sb.toString();
    }

    /** Glob pattern matching every key under this prefix (for SCAN-based cleanup). */
    public String pattern() {
        return keyPrefix + ":*";
    }

    private static String stripTrailingColons(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ':') {
            end--;
        }
        return s.substring(0, end);
    }
}
