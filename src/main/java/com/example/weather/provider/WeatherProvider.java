package com.example.weather.provider;

import com.example.weather.weather.CurrentConditions;
import com.example.weather.weather.LocationForecast;
import java.time.LocalDate;

/**
 * Port to a source of weather data (ADR 0004). Values are always metric (deg C, m/s).
 *
 * <p>Failure contract for every adapter: an unknown city throws {@link LocationNotFoundException};
 * any I/O error, timeout or upstream 5xx throws {@link WeatherProviderUnavailableException}.
 */
public interface WeatherProvider {

    CurrentConditions current(String city);

    /** Returns {@code days} consecutive daily forecasts starting at {@code startDate}. */
    LocationForecast forecast(String city, LocalDate startDate, int days);
}
