package com.example.weather.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Unit test: no Spring context, no Docker. */
class CacheKeysTest {

    @Test
    void buildsNamespacedKeys() {
        CacheKeys keys = new CacheKeys("weather");
        assertThat(keys.key("forecast", "berlin")).isEqualTo("weather:forecast:berlin");
        assertThat(keys.pattern()).isEqualTo("weather:*");
    }

    @Test
    void defaultsAndNormalisesPrefix() {
        assertThat(new CacheKeys(null).keyPrefix()).isEqualTo("weather");
        assertThat(new CacheKeys("  it:abc:: ").key("x")).isEqualTo("it:abc:x");
    }

    @Test
    void rejectsBlankParts() {
        CacheKeys keys = new CacheKeys("weather");
        assertThatThrownBy(keys::key).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> keys.key("a", " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
