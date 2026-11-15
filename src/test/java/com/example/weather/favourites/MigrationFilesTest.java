package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit test: reads the checked-in migrations from the Gradle project dir. No Spring, no Docker. */
class MigrationFilesTest {

    private static final Path MIGRATIONS =
            Path.of(System.getProperty("user.dir")).resolve("src/main/resources/db/migration");

    private static List<String> files() throws IOException {
        try (Stream<Path> s = Files.list(MIGRATIONS)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static String v2() throws IOException {
        String name = files().stream().filter(f -> f.startsWith("V2__")).findFirst().orElseThrow();
        return Files.readString(MIGRATIONS.resolve(name), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("AC-1: db/migration holds V1__baseline.sql and exactly one V2 migration")
    void exactlyOneNewMigration() throws IOException {
        assertThat(files()).containsExactly("V1__baseline.sql", "V2__create_favourite_table.sql");
    }

    @Test
    @DisplayName("AC-1: V2 contains no schema name")
    void noSchemaName() throws IOException {
        String sql = v2().toLowerCase(Locale.ROOT);
        assertThat(sql).doesNotContain("public.").doesNotContain("it_");
    }

    @Test
    @DisplayName("AC-1: V2 creates the favourite table with UNIQUE (client_id, city_key)")
    void uniqueConstraint() throws IOException {
        String sql = v2();
        assertThat(sql).contains("CREATE TABLE favourite").contains("UNIQUE (client_id, city_key)");
    }
}
