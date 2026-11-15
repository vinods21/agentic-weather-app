package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.assertj.core.util.Throwables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Unit test: binds with a plain {@link Binder}; no Spring context, no Docker. */
class FavouritesWeatherPropertiesTest {

    private static final String CONCURRENCY = "weather.favourites.weather.max-concurrency";
    private static final String TIMEOUT = "weather.favourites.weather.timeout";

    private static FavouritesWeatherProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("weather.favourites.weather", FavouritesWeatherProperties.class);
    }

    @Test
    @DisplayName("AC-18: max-concurrency defaults to 4 and timeout to 5s")
    void defaults() {
        FavouritesWeatherProperties p = bind(Map.of());
        assertThat(p.maxConcurrency()).isEqualTo(4);
        assertThat(p.timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(FavouritesWeatherProperties.defaults()).isEqualTo(p);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "20"})
    @DisplayName("AC-18: max-concurrency 1..20 is accepted")
    void acceptsConcurrency(String value) {
        assertThat(bind(Map.of(CONCURRENCY, value)).maxConcurrency()).isEqualTo(Integer.parseInt(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1s", "30s"})
    @DisplayName("AC-18: timeout 1s..30s is accepted")
    void acceptsTimeout(String value) {
        assertThat(bind(Map.of(TIMEOUT, value)).timeout()).isEqualTo(Duration.parse("PT" + value.toUpperCase()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "21", "abc"})
    @DisplayName("AC-18: an invalid max-concurrency fails binding and names the property")
    void rejectsConcurrency(String value) {
        assertThatThrownBy(() -> bind(Map.of(CONCURRENCY, value)))
                .isInstanceOf(BindException.class)
                .satisfies(e -> assertThat(e.getMessage() + " | " + Throwables.getRootCause(e).getMessage())
                        .contains(CONCURRENCY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "31s", "abc"})
    @DisplayName("AC-18: an invalid timeout fails binding and names the property")
    void rejectsTimeout(String value) {
        assertThatThrownBy(() -> bind(Map.of(TIMEOUT, value)))
                .isInstanceOf(BindException.class)
                .satisfies(e -> assertThat(e.getMessage() + " | " + Throwables.getRootCause(e).getMessage())
                        .contains(TIMEOUT));
    }

    @Test
    @DisplayName("AC-18: the range messages name the env vars and do not echo the value")
    void rangeMessages() {
        assertThatThrownBy(() -> new FavouritesWeatherProperties(4711, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("weather.favourites.weather.max-concurrency must be between 1 and 20 "
                        + "(env WEATHER_FAVOURITES_WEATHER_MAX_CONCURRENCY)");
        assertThatThrownBy(() -> new FavouritesWeatherProperties(null, Duration.ofSeconds(4711)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("weather.favourites.weather.timeout must be between 1s and 30s "
                        + "(env WEATHER_FAVOURITES_WEATHER_TIMEOUT)");
    }
}
