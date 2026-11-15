package com.example.weather.provider;

/** The provider has no data for the requested city. */
public class LocationNotFoundException extends RuntimeException {

    private final String city;

    public LocationNotFoundException(String city) {
        super("No weather data for city");
        this.city = city;
    }

    public String city() {
        return city;
    }
}
