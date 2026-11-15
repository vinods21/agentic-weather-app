package com.example.weather.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.weather.cache.CacheKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit test: no Spring context, no Docker. */
class CityNamesTest {

    private static final String SAO_PAULO_NFC = "São Paulo";
    private static final String SAO_PAULO_NFD = "São Paulo";

    @Test
    @DisplayName("AC-11: whitespace and case variants of New York give the same key")
    void newYorkVariants() {
        assertThat(CityNames.normalise("New York")).isEqualTo("new york");
        assertThat(CityNames.normalise("new   york")).isEqualTo("new york");
        assertThat(CityNames.normalise(" NEW YORK ")).isEqualTo("new york");
        assertThat(CityNames.normalise("New \tYork")).isEqualTo("new york");
    }

    @Test
    @DisplayName("AC-11: NFC and NFD forms are equal; accents are kept")
    void unicodeForms() {
        assertThat(CityNames.normalise(SAO_PAULO_NFC)).isEqualTo(CityNames.normalise(SAO_PAULO_NFD));
        assertThat(CityNames.normalise("Sao Paulo")).isNotEqualTo(CityNames.normalise(SAO_PAULO_NFC));
    }

    @ParameterizedTest
    @ValueSource(strings = {"London", "  LONDON ", "New   York", "São Paulo", "São Paulo", "Köln"})
    @DisplayName("AC-11: the geocoding cache key depends only on CityNames.normalise")
    void cacheKeysShareTheImplementation(String city) {
        CacheKeys keys = new CacheKeys("weather");
        assertThat(keys.geocoding(city)).isEqualTo(keys.geocoding(CityNames.normalise(city)));
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> CityNames.normalise(null)).isInstanceOf(NullPointerException.class);
    }
}
