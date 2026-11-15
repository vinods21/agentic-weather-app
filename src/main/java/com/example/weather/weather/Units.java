package com.example.weather.weather;

import com.fasterxml.jackson.annotation.JsonValue;

/** Measurement system requested by the client. Wire values are lowercase and case-sensitive. */
public enum Units {
    /** Degrees Celsius and metres per second. */
    METRIC("metric"),
    /** Degrees Fahrenheit and miles per hour. */
    IMPERIAL("imperial");

    private final String value;

    Units(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    /** Returns the constant whose wire value equals {@code value} exactly. */
    public static Units fromValue(String value) {
        for (Units u : values()) {
            if (u.value.equals(value)) {
                return u;
            }
        }
        throw new IllegalArgumentException("unknown units value");
    }
}
