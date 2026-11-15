package com.example.weather.weather;

/** A weather condition with a human-readable description. */
public record Condition(ConditionCode code, String description) {}
