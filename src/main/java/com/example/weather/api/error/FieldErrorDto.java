package com.example.weather.api.error;

/** One entry of the {@code errors} array of a {@code VALIDATION_FAILED} problem. */
public record FieldErrorDto(String field, String message) {}
