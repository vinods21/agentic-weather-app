package com.example.weather.cache;

import com.example.weather.weather.CurrentConditions;
import java.time.Instant;

/** Cached current conditions (metric) and the service-clock instant they were fetched upstream. */
public record CurrentEntry(CurrentConditions conditions, Instant fetchedAt) {}
