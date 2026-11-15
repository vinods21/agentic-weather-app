package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Unit test: reads the checked-in config files from the Gradle project dir. No Spring, no Docker. */
class FavouritesConfigFilesTest {

    private static final Path ROOT = Path.of(System.getProperty("user.dir"));
    private static final String ENV = "WEATHER_FAVOURITES_MAX_PER_CLIENT";

    private static Map<String, Object> yaml(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new Yaml().load(reader);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object at(Map<String, Object> map, String... keys) {
        Object node = map;
        for (String key : keys) {
            node = ((Map<String, Object>) node).get(key);
        }
        return node;
    }

    @Test
    @DisplayName("AC-14: application.yml binds max-per-client from the env var with default 20")
    void applicationYml() throws IOException {
        Map<String, Object> yml = yaml(ROOT.resolve("src/main/resources/application.yml"));
        assertThat(at(yml, "weather", "favourites", "max-per-client")).isEqualTo("${" + ENV + ":20}");
        assertThat(at(yml, "spring", "datasource", "hikari", "data-source-properties", "logServerErrorDetail"))
                .isEqualTo(false);
    }

    @Test
    @DisplayName("AC-14: .env.example documents the variable, commented out")
    void envExample() throws IOException {
        List<String> lines = Files.readAllLines(ROOT.resolve(".env.example"), StandardCharsets.UTF_8);
        assertThat(lines).anyMatch(l -> l.startsWith("# " + ENV + "="));
        assertThat(lines).noneMatch(l -> l.startsWith(ENV + "="));
    }

    @Test
    @DisplayName("AC-14: compose.yaml passes the variable to the app service with default 20")
    void compose() throws IOException {
        Map<String, Object> compose = yaml(ROOT.resolve("compose.yaml"));
        assertThat(at(compose, "services", "app", "environment", ENV)).isEqualTo("${" + ENV + ":-20}");
    }
}
