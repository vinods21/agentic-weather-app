package com.example.weather.provider;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Weather provider selection and upstream settings ({@code weather.provider.*}, ADR 0005/0006).
 *
 * <p>{@code type} is {@value #STUB} or {@value #OPEN_METEO} (exact, lower case) and defaults to
 * {@value #OPEN_METEO}. Validation errors name the property but never echo the configured value,
 * because a URL could contain credentials.
 */
@ConfigurationProperties("weather.provider")
public record WeatherProviderProperties(String type, Duration connectTimeout, Duration readTimeout, OpenMeteo openMeteo) {

    public static final String STUB = "stub";
    public static final String OPEN_METEO = "open-meteo";
    public static final List<String> ALLOWED_TYPES = List.of(STUB, OPEN_METEO);

    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(3);

    public WeatherProviderProperties {
        if (type == null) {
            type = OPEN_METEO;
        }
        if (!ALLOWED_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "weather.provider.type must be one of " + ALLOWED_TYPES + " (env WEATHER_PROVIDER)");
        }
        connectTimeout = positive(connectTimeout, DEFAULT_CONNECT_TIMEOUT, "weather.provider.connect-timeout");
        readTimeout = positive(readTimeout, DEFAULT_READ_TIMEOUT, "weather.provider.read-timeout");
        if (openMeteo == null) {
            openMeteo = new OpenMeteo(null, null);
        }
    }

    private static Duration positive(Duration value, Duration fallback, String property) {
        if (value == null) {
            return fallback;
        }
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be a positive duration");
        }
        return value;
    }

    /** Open-Meteo base URLs ({@code weather.provider.open-meteo.*}). */
    public record OpenMeteo(URI geocodingBaseUrl, URI forecastBaseUrl) {

        static final URI DEFAULT_GEOCODING_BASE_URL = URI.create("https://geocoding-api.open-meteo.com");
        static final URI DEFAULT_FORECAST_BASE_URL = URI.create("https://api.open-meteo.com");

        public OpenMeteo {
            geocodingBaseUrl = baseUrl(geocodingBaseUrl, DEFAULT_GEOCODING_BASE_URL,
                    "weather.provider.open-meteo.geocoding-base-url");
            forecastBaseUrl = baseUrl(forecastBaseUrl, DEFAULT_FORECAST_BASE_URL,
                    "weather.provider.open-meteo.forecast-base-url");
        }

        private static URI baseUrl(URI value, URI fallback, String property) {
            if (value == null) {
                return fallback;
            }
            String scheme = value.getScheme() == null ? null : value.getScheme().toLowerCase(Locale.ROOT);
            String path = value.getRawPath();
            boolean valid = value.isAbsolute()
                    && !value.isOpaque()
                    && ("http".equals(scheme) || "https".equals(scheme))
                    && value.getHost() != null
                    && value.getRawUserInfo() == null
                    && value.getRawQuery() == null
                    && value.getRawFragment() == null
                    && (path == null || path.isEmpty() || "/".equals(path));
            if (!valid) {
                throw new IllegalArgumentException(property
                        + " must be an absolute http(s) URL with a host and no user-info, path, query or fragment");
            }
            try {
                return new URI(scheme, null, value.getHost(), value.getPort(), null, null, null);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException(property + " is not a valid URL");
            }
        }
    }
}
