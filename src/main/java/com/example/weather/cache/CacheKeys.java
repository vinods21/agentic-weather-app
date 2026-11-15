package com.example.weather.cache;

import com.example.weather.weather.CityNames;
import com.example.weather.weather.Location;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Builds namespaced Redis keys ({@code <prefix>:<part>:<part>}).
 *
 * <p>The prefix comes from {@code weather.cache.key-prefix}. Integration tests set a unique
 * prefix per run so parallel or repeated runs never see each other's keys.
 *
 * <p>Every part must match {@code [A-Za-z0-9._-]+} after stripping, so raw user input can never
 * add key segments or glob characters (ADR 0007 §4). Cities are therefore only ever used as a
 * SHA-256 digest of their normalised form.
 */
@ConfigurationProperties(prefix = "weather.cache")
public record CacheKeys(String keyPrefix) {

    private static final Pattern PART = Pattern.compile("[A-Za-z0-9._-]+");
    private static final String FORMAT_VERSION = "v1";

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
            String stripped = part.strip();
            if (!PART.matcher(stripped).matches()) {
                throw new IllegalArgumentException("key parts may only contain [A-Za-z0-9._-]");
            }
            sb.append(':').append(stripped);
        }
        return sb.toString();
    }

    /** Glob pattern matching every key under this prefix (for SCAN-based cleanup). */
    public String pattern() {
        return keyPrefix + ":*";
    }

    /** {@code <prefix>:geocoding:v1:<sha256 hex of the normalised city>}. */
    public String geocoding(String city) {
        return key("geocoding", FORMAT_VERSION, sha256Hex(normaliseCity(city)));
    }

    /** {@code <prefix>:current:v1:<lat>:<lon>}. */
    public String current(Location location) {
        return key("current", FORMAT_VERSION, plain(location.latitude()), plain(location.longitude()));
    }

    /** {@code <prefix>:forecast:v1:<lat>:<lon>:<yyyy-MM-dd>:<days>}. */
    public String forecast(Location location, LocalDate startDate, int days) {
        return key("forecast", FORMAT_VERSION, plain(location.latitude()), plain(location.longitude()),
                startDate.toString(), Integer.toString(days));
    }

    /**
     * City normalisation for keys (A-3), delegating to {@link CityNames#normalise(String)}: NFC,
     * strip, collapse runs of Unicode whitespace to one space, lower-case with {@code Locale.ROOT},
     * NFC again. Accents are kept.
     */
    static String normaliseCity(String city) {
        return CityNames.normalise(city);
    }

    /** Plain-notation coordinate text, the same text the Open-Meteo adapter sends upstream. */
    static String plain(double value) {
        return BigDecimal.valueOf(value).toPlainString();
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String stripTrailingColons(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ':') {
            end--;
        }
        return s.substring(0, end);
    }
}
