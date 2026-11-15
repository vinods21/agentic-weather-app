package com.example.weather.provider;

import com.example.weather.weather.CurrentConditions;
import com.example.weather.weather.Location;
import com.example.weather.weather.LocationForecast;
import com.example.weather.weather.ObservedConditions;
import java.time.Instant;
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

    /**
     * Current conditions plus the instant they were fetched upstream (ADR 0007 §3).
     *
     * <p>{@code now} is the service-clock time truncated to seconds; adapters never read a clock.
     * The result's {@code observedAt} is {@code now}, unless an adapter serves the conditions from
     * a cache, in which case it is the {@code now} of the request that fetched them. The failure
     * contract is the same as {@link #current(String)}.
     */
    default ObservedConditions currentAt(String city, Instant now) {
        return new ObservedConditions(current(city), now);
    }

    /**
     * Resolves {@code city} to a location only (ADR 0011). Callers pass the city as the user sent
     * it (stripped), never a pre-normalised form. The failure contract is the same as
     * {@link #current(String)}: an unknown city throws {@link LocationNotFoundException}, an
     * upstream problem {@link WeatherProviderUnavailableException}. The default fetches the
     * current conditions; adapters that can resolve a location more cheaply override it.
     */
    default Location locate(String city) {
        return current(city).location();
    }

    /** Returns {@code days} consecutive daily forecasts starting at {@code startDate}. */
    LocationForecast forecast(String city, LocalDate startDate, int days);
}
