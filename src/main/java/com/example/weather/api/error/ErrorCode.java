package com.example.weather.api.error;

import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * Closed set of API error codes (ADR 0003). Codes may be added but never changed; the OpenAPI
 * {@code Problem.code} enum must list exactly these names.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed"),
    LOCATION_NOT_FOUND(HttpStatus.NOT_FOUND, "location-not-found", "Location not found"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "not-found", "Not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed", "Method not allowed"),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "upstream-unavailable", "Upstream unavailable"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error"),
    FAVOURITE_ALREADY_EXISTS(HttpStatus.CONFLICT, "favourite-already-exists", "Favourite already exists"),
    FAVOURITES_LIMIT_REACHED(HttpStatus.CONFLICT, "favourites-limit-reached", "Favourites limit reached");

    private static final String TYPE_BASE = "https://weather.example.com/problems/";

    private final HttpStatus status;
    private final String slug;
    private final String title;

    ErrorCode(HttpStatus status, String slug, String title) {
        this.status = status;
        this.slug = slug;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public URI type() {
        return URI.create(TYPE_BASE + slug);
    }
}
