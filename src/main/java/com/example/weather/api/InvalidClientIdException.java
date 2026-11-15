package com.example.weather.api;

/** A missing or ill-formed {@code X-Client-Id} (400 VALIDATION_FAILED). Never holds the value. */
public class InvalidClientIdException extends RuntimeException {

    public InvalidClientIdException() {
        super("Invalid " + ClientId.HEADER + " header.", null, false, false);
    }
}
