package com.example.weather.cache;

/** A cached value failed validation. The reason is a fixed text, never value content. */
class UnreadableCacheEntryException extends RuntimeException {

    UnreadableCacheEntryException(String reason) {
        super(reason, null, false, false);
    }
}
