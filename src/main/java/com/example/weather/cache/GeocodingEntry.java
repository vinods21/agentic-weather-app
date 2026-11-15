package com.example.weather.cache;

import com.example.weather.weather.Location;

/** A cached geocoding result: a resolved location, or "not found" (ADR 0007 §6). */
public sealed interface GeocodingEntry {

    record Found(Location location) implements GeocodingEntry {}

    record NotFound() implements GeocodingEntry {}
}
