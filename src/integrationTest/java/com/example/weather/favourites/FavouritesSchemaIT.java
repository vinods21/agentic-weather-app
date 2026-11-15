package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.weather.IntegrationTestBase;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/** AC-1: the V2 migration ran in the per-run schema and the unique constraint is enforced by the database. */
class FavouritesSchemaIT extends IntegrationTestBase {

    private final String clientId = "it-fav-schema-" + UUID.randomUUID();

    @AfterEach
    void deleteProbeRows() {
        jdbc.update("DELETE FROM favourite WHERE client_id IN (?, ?)", clientId, clientId + "-b");
    }

    private String schema() {
        return jdbc.queryForObject("SELECT current_schema()", String.class);
    }

    @Test
    @DisplayName("AC-1: flyway_schema_history in the per-run schema has version 2 with success = true")
    void ac1_migrationApplied() {
        // The datasource URL selects the run schema; it is it_<run-id>, never public.
        String runId = System.getProperty("it.run-id");
        assertThat(schema()).isNotEqualTo("public").startsWith("it_");
        if (runId != null) {
            assertThat(schema()).isEqualTo("it_" + runId);
        }

        List<Map<String, Object>> v2 = jdbc.queryForList(
                "SELECT version, success, script FROM flyway_schema_history WHERE version = '2'");
        assertThat(v2).hasSize(1);
        assertThat(v2.get(0).get("success")).isEqualTo(true);
        assertThat((String) v2.get(0).get("script")).startsWith("V2__").endsWith(".sql");

        Integer later = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL AND version NOT IN ('1', '2')",
                Integer.class);
        assertThat(later).as("exactly one new migration").isZero();
    }

    @Test
    @DisplayName("AC-1: the favourite table and its (client_id, city_key) unique constraint exist in the per-run schema")
    void ac1_tableAndConstraintExist() {
        Integer tables = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() "
                        + "AND table_name = 'favourite'", Integer.class);
        assertThat(tables).isEqualTo(1);

        List<String> uniqueColumns = jdbc.queryForList(
                "SELECT k.column_name FROM information_schema.table_constraints c "
                        + "JOIN information_schema.key_column_usage k "
                        + "  ON k.constraint_name = c.constraint_name AND k.constraint_schema = c.constraint_schema "
                        + "WHERE c.table_schema = current_schema() AND c.table_name = 'favourite' "
                        + "AND c.constraint_type = 'UNIQUE' ORDER BY k.ordinal_position", String.class);
        assertThat(uniqueColumns).containsExactly("client_id", "city_key");
    }

    @Test
    @DisplayName("AC-1: a second row with the same client id and city key inserted through JdbcTemplate fails")
    void ac1_uniqueConstraintEnforced() {
        insert(UUID.randomUUID(), clientId, "london");
        // Another client with the same key, and the same client with another key, are fine.
        insert(UUID.randomUUID(), clientId + "-b", "london");
        insert(UUID.randomUUID(), clientId, "paris");

        assertThatThrownBy(() -> insert(UUID.randomUUID(), clientId, "london"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private void insert(UUID id, String client, String key) {
        Timestamp now = Timestamp.from(Instant.parse("2026-11-15T10:00:00Z"));
        jdbc.update("INSERT INTO favourite (id, client_id, city, city_key, label, location_name, location_country, "
                        + "location_latitude, location_longitude, position, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NULL, 'London', 'GB', 51.5074, -0.1278, 1, ?, ?)",
                id, client, key, key, now, now);
    }
}
