package com.example.weather.api;

import java.util.regex.Pattern;

/**
 * The opaque {@code X-Client-Id} partition key of client-scoped resources (ADR 0009). It is not a
 * credential. The value is case-sensitive and never stripped; {@link #toString()} is redacted so a
 * careless log line can't leak it.
 */
public record ClientId(String value) {

    public static final String HEADER = "X-Client-Id";
    public static final String REGEX = "^[A-Za-z0-9._-]{1,64}$";
    public static final Pattern PATTERN = Pattern.compile(REGEX);
    public static final String MESSAGE = "must match " + REGEX;

    public ClientId {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new InvalidClientIdException();
        }
    }

    /** Validates a raw header value; null, empty or ill-formed values throw {@link InvalidClientIdException}. */
    public static ClientId parse(String raw) {
        return new ClientId(raw);
    }

    @Override
    public String toString() {
        return "ClientId[***]";
    }
}
