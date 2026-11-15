package com.example.weather.provider.openmeteo;

/**
 * A 2xx upstream body that can't be mapped to the v1 contract (ADR 0005/0006). The reason is a
 * fixed string chosen in code and never contains upstream text, so it is safe to log.
 */
class InvalidUpstreamResponseException extends RuntimeException {

    private final String reason;

    InvalidUpstreamResponseException(String reason) {
        super(reason, null, false, false);
        this.reason = reason;
    }

    String reason() {
        return reason;
    }
}
