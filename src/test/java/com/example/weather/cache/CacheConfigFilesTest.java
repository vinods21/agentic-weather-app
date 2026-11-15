package com.example.weather.cache;

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
class CacheConfigFilesTest {

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
    @DisplayName("AC-1: application.yml binds every cache and Redis timeout property from an env var with a default")
    void applicationYmlPlaceholders() throws IOException {
        Map<String, Object> yml = yaml(ROOT.resolve("src/main/resources/application.yml"));
        assertThat(at(yml, "weather", "cache", "enabled")).isEqualTo("${WEATHER_CACHE_ENABLED:true}");
        assertThat(at(yml, "weather", "cache", "geocoding-ttl")).isEqualTo("${WEATHER_CACHE_GEOCODING_TTL:7d}");
        assertThat(at(yml, "weather", "cache", "not-found-ttl")).isEqualTo("${WEATHER_CACHE_NOT_FOUND_TTL:1h}");
        assertThat(at(yml, "weather", "cache", "current-ttl")).isEqualTo("${WEATHER_CACHE_CURRENT_TTL:10m}");
        assertThat(at(yml, "weather", "cache", "forecast-ttl")).isEqualTo("${WEATHER_CACHE_FORECAST_TTL:1h}");
        assertThat(at(yml, "spring", "data", "redis", "timeout")).isEqualTo("${REDIS_TIMEOUT:500ms}");
        assertThat(at(yml, "spring", "data", "redis", "connect-timeout")).isEqualTo("${REDIS_CONNECT_TIMEOUT:500ms}");
    }

    @ParameterizedTest
    @DisplayName("AC-1: each cache env var is documented, commented out, in .env.example")
    @ValueSource(strings = {
        "WEATHER_CACHE_ENABLED", "WEATHER_CACHE_GEOCODING_TTL", "WEATHER_CACHE_NOT_FOUND_TTL",
        "WEATHER_CACHE_CURRENT_TTL", "WEATHER_CACHE_FORECAST_TTL", "REDIS_TIMEOUT", "REDIS_CONNECT_TIMEOUT"
    })
    void envExampleDocumentsVariable(String name) throws IOException {
        List<String> lines = Files.readAllLines(ROOT.resolve(".env.example"), StandardCharsets.UTF_8);
        assertThat(lines).anyMatch(l -> l.startsWith("# " + name + "="));
        assertThat(lines).noneMatch(l -> l.startsWith(name + "="));
    }

    @ParameterizedTest
    @DisplayName("AC-1: compose.yaml passes each cache env var to the app service with a default")
    @ValueSource(strings = {
        "WEATHER_CACHE_ENABLED", "WEATHER_CACHE_GEOCODING_TTL", "WEATHER_CACHE_NOT_FOUND_TTL",
        "WEATHER_CACHE_CURRENT_TTL", "WEATHER_CACHE_FORECAST_TTL", "REDIS_TIMEOUT", "REDIS_CONNECT_TIMEOUT"
    })
    void composePassesVariable(String name) throws IOException {
        Map<String, Object> compose = yaml(ROOT.resolve("compose.yaml"));
        Object value = at(compose, "services", "app", "environment", name);
        assertThat(value).isInstanceOf(String.class);
        assertThat((String) value).startsWith("${" + name + ":-").endsWith("}");
    }
}
