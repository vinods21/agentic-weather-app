package com.example.weather.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.assertj.core.util.Throwables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Unit test: binds with a plain {@link Binder}; no Spring context, no Docker. */
class WeatherCachePropertiesTest {

    private static WeatherCacheProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("weather.cache", WeatherCacheProperties.class);
    }

    @Test
    @DisplayName("AC-1: defaults are enabled, 7d / 1h / 10m / 1h")
    void defaults() {
        WeatherCacheProperties p = bind(Map.of());
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.geocodingTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(p.notFoundTtl()).isEqualTo(Duration.ofHours(1));
        assertThat(p.currentTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(p.forecastTtl()).isEqualTo(Duration.ofHours(1));
        assertThat(WeatherCacheProperties.defaults()).isEqualTo(p);
    }

    @Test
    @DisplayName("AC-1: values bind from Boot duration strings; key-prefix (CacheKeys) is ignored")
    void bindsValues() {
        WeatherCacheProperties p = bind(Map.of(
                "weather.cache.key-prefix", "it:x",
                "weather.cache.enabled", "false",
                "weather.cache.geocoding-ttl", "2d",
                "weather.cache.not-found-ttl", "30m",
                "weather.cache.current-ttl", "1s",
                "weather.cache.forecast-ttl", "500ms"));
        assertThat(p.isEnabled()).isFalse();
        assertThat(p.geocodingTtl()).isEqualTo(Duration.ofDays(2));
        assertThat(p.notFoundTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.currentTtl()).isEqualTo(Duration.ofSeconds(1));
        assertThat(p.forecastTtl()).isEqualTo(Duration.ofMillis(500));
    }

    static Stream<Arguments> invalidTtls() {
        return Stream.of("geocoding-ttl", "not-found-ttl", "current-ttl", "forecast-ttl")
                .flatMap(name -> Stream.of("0s", "-5m", "abc").map(value -> Arguments.of(name, value)));
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidTtls")
    @DisplayName("AC-2: a zero, negative or unparseable TTL fails binding and names the property")
    void invalidTtl(String name, String value) {
        Map<String, String> values = new HashMap<>();
        values.put("weather.cache." + name, value);

        assertThatThrownBy(() -> bind(values))
                .isInstanceOf(BindException.class)
                .satisfies(e -> assertThat(e.getMessage() + " | " + Throwables.getRootCause(e).getMessage())
                        .contains("weather.cache." + name));
    }

    @Test
    @DisplayName("AC-2: the non-positive TTL message names the env var and does not echo the value")
    void nonPositiveMessage() {
        assertThatThrownBy(() -> new WeatherCacheProperties(null, null, Duration.ofMinutes(-5), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("weather.cache.not-found-ttl must be a positive duration (env WEATHER_CACHE_NOT_FOUND_TTL)");
    }
}
