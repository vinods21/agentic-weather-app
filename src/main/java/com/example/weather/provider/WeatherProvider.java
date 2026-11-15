package com.example.weather.provider;

import com.example.weather.weather.CurrentConditions;
import com.example.weather.weather.LocationForecast;
import java.time.LocalDate;

/**
 * Port to a source of weather data (ADR 0005, superseding 0004). Values are always metric
 * (deg C, m/s). The single bean is selected by {@code weather.provider.type}.
 *
 * <p>Failure contract for every adapter: an unknown city throws {@link LocationNotFoundException};
 * any I/O error, timeout, non-2xx upstream status or malformed upstream response throws
 * {@link WeatherProviderUnavailableException}.
 * Exception messages must not contain credentials or URLs with query strings. The API layer logs
 * only the exception and cause class names for a 503, so an adapter that needs more detail must
 * log it itself, sanitised.
 */
public interface WeatherProvider {

    CurrentConditions current(String city);

    /** Returns {@code days} consecutive daily forecasts starting at {@code startDate}. */
    LocationForecast forecast(String city, LocalDate startDate, int days);
}
