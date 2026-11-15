package com.example.weather.favourites;

import com.example.weather.weather.Location;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link FavouriteRepository} on {@link JdbcClient} (ADR 0008 §1). All SQL is parameterised and
 * every statement filters by {@code client_id}. Instants are bound as UTC {@link OffsetDateTime}s.
 */
@Repository
public class JdbcFavouriteRepository implements FavouriteRepository {

    private static final String COLUMNS = "id, client_id, city, label, location_name, location_country, "
            + "location_latitude, location_longitude, position, created_at, updated_at";

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public JdbcFavouriteRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Favourite> findAll(String clientId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM favourite WHERE client_id = :clientId "
                        + "ORDER BY position, created_at, id")
                .param("clientId", clientId)
                .query(JdbcFavouriteRepository::map)
                .list();
    }

    @Override
    public Optional<Favourite> find(String clientId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM favourite WHERE client_id = :clientId AND id = :id")
                .param("clientId", clientId)
                .param("id", id)
                .query(JdbcFavouriteRepository::map)
                .optional();
    }

    @Override
    public boolean existsByKey(String clientId, String cityKey) {
        Boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM favourite WHERE client_id = :clientId "
                        + "AND city_key = :key)")
                .param("clientId", clientId)
                .param("key", cityKey)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public int count(String clientId) {
        Long count = jdbc.sql("SELECT count(*) FROM favourite WHERE client_id = :clientId")
                .param("clientId", clientId)
                .query(Long.class)
                .single();
        return count == null ? 0 : count.intValue();
    }

    @Override
    public void lockClient(String clientId) {
        // pg_advisory_xact_lock returns void: run it as a query and discard the row.
        jdbc.sql("SELECT pg_advisory_xact_lock(:ns, hashtext(:clientId))")
                .param("ns", LOCK_NAMESPACE)
                .param("clientId", clientId)
                .query((rs, n) -> Boolean.TRUE)
                .list();
    }

    @Override
    public void insert(Favourite f, String cityKey) {
        jdbc.sql("INSERT INTO favourite (id, client_id, city, city_key, label, location_name, location_country, "
                        + "location_latitude, location_longitude, position, created_at, updated_at) "
                        + "VALUES (:id, :clientId, :city, :key, :label, :name, :country, :lat, :lon, "
                        + "(SELECT COALESCE(MAX(position), 0) + 1 FROM favourite WHERE client_id = :clientId), "
                        + ":createdAt, :updatedAt)")
                .param("id", f.id())
                .param("clientId", f.clientId())
                .param("city", f.city())
                .param("key", cityKey)
                .param("label", f.label(), Types.VARCHAR)
                .param("name", f.location().name())
                .param("country", f.location().country())
                .param("lat", f.location().latitude())
                .param("lon", f.location().longitude())
                .param("createdAt", utc(f.createdAt()))
                .param("updatedAt", utc(f.updatedAt()))
                .update();
    }

    @Override
    public Optional<Favourite> updateLabel(String clientId, UUID id, String label, Instant updatedAt) {
        return jdbc.sql("UPDATE favourite SET label = :label, updated_at = :updatedAt "
                        + "WHERE client_id = :clientId AND id = :id RETURNING " + COLUMNS)
                .param("label", label, Types.VARCHAR)
                .param("updatedAt", utc(updatedAt))
                .param("clientId", clientId)
                .param("id", id)
                .query(JdbcFavouriteRepository::map)
                .optional();
    }

    @Override
    public List<UUID> findIds(String clientId) {
        return jdbc.sql("SELECT id FROM favourite WHERE client_id = :clientId")
                .param("clientId", clientId)
                .query((rs, n) -> rs.getObject("id", UUID.class))
                .list();
    }

    @Override
    public void updatePositions(String clientId, List<UUID> orderedIds) {
        List<Object[]> args = new ArrayList<>(orderedIds.size());
        for (int i = 0; i < orderedIds.size(); i++) {
            args.add(new Object[] {i + 1, clientId, orderedIds.get(i)});
        }
        jdbcTemplate.batchUpdate("UPDATE favourite SET position = ? WHERE client_id = ? AND id = ?", args,
                new int[] {Types.INTEGER, Types.VARCHAR, Types.OTHER});
    }

    @Override
    public boolean delete(String clientId, UUID id) {
        return jdbc.sql("DELETE FROM favourite WHERE client_id = :clientId AND id = :id")
                .param("clientId", clientId)
                .param("id", id)
                .update() > 0;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Favourite map(ResultSet rs, int rowNum) throws SQLException {
        return new Favourite(
                rs.getObject("id", UUID.class),
                rs.getString("client_id"),
                rs.getString("city"),
                rs.getString("label"),
                new Location(rs.getString("location_name"), rs.getString("location_country"),
                        rs.getDouble("location_latitude"), rs.getDouble("location_longitude")),
                rs.getInt("position"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
