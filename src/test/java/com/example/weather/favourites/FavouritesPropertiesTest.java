package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
class FavouritesPropertiesTest {

    private static FavouritesProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("weather.favourites", FavouritesProperties.class);
    }

    @Test
    @DisplayName("AC-14: max-per-client defaults to 20")
    void defaults() {
        assertThat(bind(Map.of()).maxPerClient()).isEqualTo(20);
        assertThat(FavouritesProperties.defaults()).isEqualTo(bind(Map.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "3", "100"})
    @DisplayName("AC-14: values 1..100 are accepted")
    void accepts(String value) {
        assertThat(bind(Map.of("weather.favourites.max-per-client", value)).maxPerClient())
                .isEqualTo(Integer.parseInt(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "abc"})
    @DisplayName("AC-14: an out-of-range or unparseable value fails binding and names the property")
    void rejects(String value) {
        assertThatThrownBy(() -> bind(Map.of("weather.favourites.max-per-client", value)))
                .isInstanceOf(BindException.class)
                .satisfies(e -> assertThat(e.getMessage() + " | " + Throwables.getRootCause(e).getMessage())
                        .contains("weather.favourites.max-per-client"));
    }

    @Test
    @DisplayName("AC-14: the range message names the env var and does not echo the value")
    void rangeMessage() {
        assertThatThrownBy(() -> new FavouritesProperties(4711))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("weather.favourites.max-per-client must be between 1 and 100 "
                        + "(env WEATHER_FAVOURITES_MAX_PER_CLIENT)");
    }
}
