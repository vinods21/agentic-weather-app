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
     * Current conditions at an already resolved, stored location, without geocoding (ADR 0012).
     *
     * <p>{@code now} has the same meaning as in {@link #currentAt(String, Instant)}. The returned
     * conditions describe the location that was passed in. The failure contract is the same as
     * {@link #current(String)}: {@link LocationNotFoundException} when the adapter has no data for
     * the location, {@link WeatherProviderUnavailableException} for any upstream problem. The
     * default falls back to resolving the stored canonical name through
     * {@link #currentAt(String, Instant)}; adapters that can look up by coordinates override it.
     */
    default ObservedConditions currentAtLocation(Location location, Instant now) {
        return currentAt(location.name(), now);
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

    /**
     * Daily forecasts at an already resolved, stored location, without geocoding (ADR 0015).
     *
     * <p>{@code startDate} and {@code days} have the same meaning as in
     * {@link #forecast(String, LocalDate, int)}: the caller computes the UTC start date, adapters never
     * read a clock, and {@code days} outside 1..7 is an {@link IllegalArgumentException}. The returned
     * forecast describes the location that was passed in. The failure contract is the same as
     * {@link #forecast(String, LocalDate, int)}: {@link LocationNotFoundException} when the adapter has
     * no data for the location, {@link WeatherProviderUnavailableException} for any upstream problem.
     * The default falls back to resolving the stored canonical name through
     * {@link #forecast(String, LocalDate, int)}; adapters that can look up by coordinates override it.
     */
    default LocationForecast forecastAtLocation(Location location, LocalDate startDate, int days) {
        return forecast(location.name(), startDate, days);
    }
}
