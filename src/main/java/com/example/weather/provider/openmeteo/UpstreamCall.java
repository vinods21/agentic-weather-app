package com.example.weather.provider.openmeteo;

/** The two Open-Meteo calls: a log name and the path (never a query) for the WARN line (ADR 0006). */
enum UpstreamCall {
    GEOCODING("geocoding", "/v1/search"),
    FORECAST("forecast", "/v1/forecast");

    private final String logName;
    private final String path;

    UpstreamCall(String logName, String path) {
        this.logName = logName;
        this.path = path;
    }

    String logName() {
        return logName;
    }

    String path() {
        return path;
    }
}
