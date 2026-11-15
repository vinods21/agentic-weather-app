package com.example.weather.favourites;

import com.example.weather.weather.Location;
import java.time.Instant;
import java.util.UUID;

/**
 * A saved favourite of one client (ADR 0008/0009). {@code label} may be null. {@code position}
 * only orders the client's list and is never exposed.
 *
 * <p>{@link #toString()} omits the client id and the label, so a careless log line can't leak them.
 */
public record Favourite(UUID id, String clientId, String city, String label, Location location, int position,
                        Instant createdAt, Instant updatedAt) {

    @Override
    public String toString() {
        return "Favourite[id=" + id + ", city=" + city + ", location=" + location + ", position=" + position
                + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt + "]";
    }
}
