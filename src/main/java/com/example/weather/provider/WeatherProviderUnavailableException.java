package com.example.weather.provider;

/**
 * The weather source failed or timed out. The message is never sent in responses and is not logged
 * by the API layer; it must not contain credentials or URLs with query strings (see
 * {@link WeatherProvider}).
 */
public class WeatherProviderUnavailableException extends RuntimeException {

    public WeatherProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
