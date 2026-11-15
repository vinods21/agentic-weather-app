package com.example.weather.provider;

/** The weather source failed or timed out. The message is for logs only, never for responses. */
public class WeatherProviderUnavailableException extends RuntimeException {

    public WeatherProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
