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
class FavouritesWeatherConfigFilesTest {

    private static final Path ROOT = Path.of(System.getProperty("user.dir"));
    private static final String CONCURRENCY_ENV = "WEATHER_FAVOURITES_WEATHER_MAX_CONCURRENCY";
    private static final String TIMEOUT_ENV = "WEATHER_FAVOURITES_WEATHER_TIMEOUT";

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
    @DisplayName("AC-18: application.yml binds max-concurrency and timeout from env vars with defaults 4 and 5s")
    void applicationYml() throws IOException {
        Map<String, Object> yml = yaml(ROOT.resolve("src/main/resources/application.yml"));
        assertThat(at(yml, "weather", "favourites", "weather", "max-concurrency"))
                .isEqualTo("${" + CONCURRENCY_ENV + ":4}");
        assertThat(at(yml, "weather", "favourites", "weather", "timeout")).isEqualTo("${" + TIMEOUT_ENV + ":5s}");
    }

    @Test
    @DisplayName("AC-18: .env.example documents both variables, commented out")
    void envExample() throws IOException {
        List<String> lines = Files.readAllLines(ROOT.resolve(".env.example"), StandardCharsets.UTF_8);
        for (String env : List.of(CONCURRENCY_ENV, TIMEOUT_ENV)) {
            assertThat(lines).anyMatch(l -> l.startsWith("# " + env + "="));
            assertThat(lines).noneMatch(l -> l.startsWith(env + "="));
        }
    }

    @Test
    @DisplayName("AC-18: compose.yaml passes both variables to the app service with defaults")
    void compose() throws IOException {
        Map<String, Object> compose = yaml(ROOT.resolve("compose.yaml"));
        assertThat(at(compose, "services", "app", "environment", CONCURRENCY_ENV))
                .isEqualTo("${" + CONCURRENCY_ENV + ":-4}");
        assertThat(at(compose, "services", "app", "environment", TIMEOUT_ENV))
                .isEqualTo("${" + TIMEOUT_ENV + ":-5s}");
    }
}
