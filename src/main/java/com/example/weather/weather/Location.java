package com.example.weather.weather;

/** A resolved location. {@code country} is ISO 3166-1 alpha-2. */
public record Location(String name, String country, double latitude, double longitude) {}
