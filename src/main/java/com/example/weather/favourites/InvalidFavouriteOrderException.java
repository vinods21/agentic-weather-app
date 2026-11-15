package com.example.weather.favourites;

/**
 * A reorder request whose {@code ids} don't describe the client's list (400 VALIDATION_FAILED on
 * field {@code ids}). The message is always one of the fixed texts below and never echoes input.
 */
public class InvalidFavouriteOrderException extends RuntimeException {

    public static final String NOT_NULL = "must not be null";
    public static final String NOT_UUIDS = "must contain only favourite ids (UUIDs)";
    public static final String DUPLICATES = "must not contain duplicate ids";
    public static final String SET_MISMATCH = "must contain every favourite id of this client exactly once";

    public InvalidFavouriteOrderException(String message) {
        super(message, null, false, false);
    }
}
