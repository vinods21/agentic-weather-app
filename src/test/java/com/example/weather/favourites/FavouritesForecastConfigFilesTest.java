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

/**
 * Unit test (AC-21, ADR 0016 §2): the forecast endpoint adds no configuration of its own. Reads the
 * checked-in config files from the Gradle project dir. No Spring, no Docker.
 */
class FavouritesForecastConfigFilesTest {

    private static final Path ROOT = Path.of(System.getProperty("user.dir"));
    private static final String FORECAST_ENV_PREFIX = "WEATHER_FAVOURITES_FORECAST";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> favourites() throws IOException {
        try (Reader reader = Files.newBufferedReader(ROOT.resolve("src/main/resources/application.yml"),
                StandardCharsets.UTF_8)) {
            Map<String, Object> yml = new Yaml().load(reader);
            return (Map<String, Object>) ((Map<String, Object>) yml.get("weather")).get("favourites");
        }
    }

    @Test
    @DisplayName("AC-21: application.yml has no weather.favourites.forecast key; the shared weather keys remain")
    void noForecastKey() throws IOException {
        Map<String, Object> favourites = favourites();
        assertThat(favourites).doesNotContainKey("forecast");
        assertThat(favourites).containsKey("weather");
    }

    @Test
    @DisplayName("AC-21: no WEATHER_FAVOURITES_FORECAST variable in application.yml, .env.example or compose.yaml")
    void noForecastEnvVars() throws IOException {
        for (String file : List.of("src/main/resources/application.yml", ".env.example", "compose.yaml")) {
            assertThat(Files.readString(ROOT.resolve(file), StandardCharsets.UTF_8)).as(file)
                    .doesNotContain(FORECAST_ENV_PREFIX);
        }
    }
}
