package com.example.weather.weather;

import java.time.Instant;

/** Provider conditions plus the service-clock instant at which they were fetched upstream (ADR 0007 §3). */
public record ObservedConditions(CurrentConditions conditions, Instant observedAt) {}
