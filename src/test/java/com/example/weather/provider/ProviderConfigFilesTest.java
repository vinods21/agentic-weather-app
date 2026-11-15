package com.example.weather.provider;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

/** Unit test: reads the checked-in config files from the Gradle project dir. No Spring, no Docker. */
class ProviderConfigFilesTest {

    private static final Path ROOT = Path.of(System.getProperty("user.dir"));

    @SuppressWarnings("unchecked")
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
    @DisplayName("AC-4: application.yml binds every provider property from an env var with a default")
    void applicationYmlPlaceholders() throws IOException {
        Map<String, Object> yml = yaml(ROOT.resolve("src/main/resources/application.yml"));
        assertThat(at(yml, "weather", "provider", "type")).isEqualTo("${WEATHER_PROVIDER:open-meteo}");
        assertThat(at(yml, "weather", "provider", "connect-timeout")).isEqualTo("${WEATHER_PROVIDER_CONNECT_TIMEOUT:2s}");
        assertThat(at(yml, "weather", "provider", "read-timeout")).isEqualTo("${WEATHER_PROVIDER_READ_TIMEOUT:3s}");
        assertThat(at(yml, "weather", "provider", "open-meteo", "geocoding-base-url"))
                .isEqualTo("${WEATHER_PROVIDER_GEOCODING_BASE_URL:https://geocoding-api.open-meteo.com}");
        assertThat(at(yml, "weather", "provider", "open-meteo", "forecast-base-url"))
                .isEqualTo("${WEATHER_PROVIDER_FORECAST_BASE_URL:https://api.open-meteo.com}");
    }

    @ParameterizedTest
    @DisplayName("AC-4: each provider env var is documented, commented out, in .env.example")
    @ValueSource(strings = {
        "WEATHER_PROVIDER", "WEATHER_PROVIDER_GEOCODING_BASE_URL", "WEATHER_PROVIDER_FORECAST_BASE_URL",
        "WEATHER_PROVIDER_CONNECT_TIMEOUT", "WEATHER_PROVIDER_READ_TIMEOUT"
    })
    void envExampleDocumentsVariable(String name) throws IOException {
        List<String> lines = Files.readAllLines(ROOT.resolve(".env.example"), StandardCharsets.UTF_8);
        assertThat(lines).anyMatch(l -> l.startsWith("# " + name + "="));
        assertThat(lines).noneMatch(l -> l.startsWith(name + "="));
    }

    @ParameterizedTest
    @DisplayName("AC-4: compose.yaml passes each provider env var to the app service with a default")
    @ValueSource(strings = {
        "WEATHER_PROVIDER", "WEATHER_PROVIDER_GEOCODING_BASE_URL", "WEATHER_PROVIDER_FORECAST_BASE_URL",
        "WEATHER_PROVIDER_CONNECT_TIMEOUT", "WEATHER_PROVIDER_READ_TIMEOUT"
    })
    void composePassesVariable(String name) throws IOException {
        Map<String, Object> compose = yaml(ROOT.resolve("compose.yaml"));
        Object value = at(compose, "services", "app", "environment", name);
        assertThat(value).isInstanceOf(String.class);
        assertThat((String) value).startsWith("${" + name + ":-").endsWith("}");
    }
}
