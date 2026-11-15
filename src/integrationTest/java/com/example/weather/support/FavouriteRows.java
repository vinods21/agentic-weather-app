package com.example.weather.support;

import com.example.weather.weather.CityNames;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Direct inserts into the {@code favourite} table (test code only), for favourites whose stored
 * location the API could not produce: coordinates the stub or the fake's geocoding don't know
 * (AC-7, AC-14, AC-16). The caller deletes the rows of its client id afterwards.
 */
public final class FavouriteRows {

    private FavouriteRows() {
    }

    /** Inserts one row and returns its id; {@code city_key} is {@code CityNames.normalise(city)}. */
    public static UUID insert(JdbcTemplate jdbc, String clientId, String city, String name, String country,
                              double latitude, double longitude, int position, Instant at) {
        return insert(jdbc, clientId, city, null, name, country, latitude, longitude, position, at);
    }

    /** As {@link #insert(JdbcTemplate, String, String, String, String, double, double, int, Instant)} with a label. */
    public static UUID insert(JdbcTemplate jdbc, String clientId, String city, String label, String name,
                              String country, double latitude, double longitude, int position, Instant at) {
        UUID id = UUID.randomUUID();
        Timestamp ts = Timestamp.from(at);
        jdbc.update("INSERT INTO favourite (id, client_id, city, city_key, label, location_name, location_country, "
                        + "location_latitude, location_longitude, position, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, clientId, city, CityNames.normalise(city), label, name, country, latitude, longitude,
                position, ts, ts);
        return id;
    }

    /** Deletes every row of {@code clientId}. */
    public static int deleteAll(JdbcTemplate jdbc, String clientId) {
        return jdbc.update("DELETE FROM favourite WHERE client_id = ?", clientId);
    }
}
