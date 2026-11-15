package com.example.weather.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.weather.CityNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit test: no Spring context, no Docker. */
class CacheKeysNormalisationTest {

    @ParameterizedTest
    @ValueSource(strings = {"London", "  LONDON ", "New   York", " NEW YORK ", "São Paulo", "São Paulo",
        "Sao Paulo", "İstanbul", "New \tYork"})
    @DisplayName("AC-11: CacheKeys.normaliseCity delegates to CityNames.normalise")
    void delegates(String city) {
        assertThat(CacheKeys.normaliseCity(city)).isEqualTo(CityNames.normalise(city));
    }
}
