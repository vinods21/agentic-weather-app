package com.example.weather.weather;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The one city-name normalisation of the service. It is used for geocoding cache keys
 * ({@code CacheKeys}) and for the favourites duplicate key, so the two can never drift apart.
 */
public final class CityNames {

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private CityNames() {}

    /**
     * NFC, strip, collapse runs of Unicode whitespace to one space, lower-case with
     * {@link Locale#ROOT}, NFC again. Accents are kept.
     */
    public static String normalise(String city) {
        Objects.requireNonNull(city, "city");
        String s = Normalizer.normalize(city, Normalizer.Form.NFC).strip();
        s = WHITESPACE_RUN.matcher(s).replaceAll(" ");
        s = s.toLowerCase(Locale.ROOT);
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }
}
