package com.example.weather.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.weather.weather.Location;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit test: no Spring context, no Docker. */
class CacheKeysTest {

    private static final CacheKeys KEYS = new CacheKeys("weather");
    private static final Location SAO_PAULO = new Location("São Paulo", "BR", -23.5475, -46.63611);

    @Test
    void buildsNamespacedKeys() {
        CacheKeys keys = new CacheKeys("weather");
        assertThat(keys.key("forecast", "berlin")).isEqualTo("weather:forecast:berlin");
        assertThat(keys.key("probe")).isEqualTo("weather:probe");
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

    @ParameterizedTest
    @ValueSource(strings = {"a:b", "a*", "a?", "a[1]", "a b", "ä", "a/b"})
    @DisplayName("AC-10: a part outside [A-Za-z0-9._-] is rejected")
    void rejectsInvalidParts(String part) {
        assertThatThrownBy(() -> KEYS.key("x", part)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "London|LONDON",
        "London|london",
        "New York|new   york",
        "New York|'  New\tYork '",
        "New York|New York",
        "New York|New  York",
        "São Paulo|São Paulo",
        "SÃO PAULO|são paulo",
    })
    @DisplayName("AC-7: case, whitespace runs and Unicode normal form share one geocoding key")
    void normalisationPairs(String a, String b) {
        assertThat(KEYS.geocoding(b)).isEqualTo(KEYS.geocoding(a));
    }

    @Test
    @DisplayName("AC-7: normaliseCity keeps accents and collapses whitespace to one space")
    void normaliseCity() {
        assertThat(CacheKeys.normaliseCity("  New \t York ")).isEqualTo("new york");
        assertThat(CacheKeys.normaliseCity("São Paulo")).isEqualTo("são paulo");
        assertThat(KEYS.geocoding("Sao Paulo")).isNotEqualTo(KEYS.geocoding("São Paulo"));
    }

    @Test
    @DisplayName("AC-10: special-character cities give distinct keys of only [A-Za-z0-9:._-]")
    void specialCharacterCities() {
        List<String> cities = List.of("a:b", "a_b", "a*", "a?", "a[1]", "a b");
        Set<String> keys = new HashSet<>();
        for (String city : cities) {
            String key = KEYS.geocoding(city);
            assertThat(key).matches("^weather:geocoding:v1:[0-9a-f]{64}$");
            assertThat(key).matches("^weather:[A-Za-z0-9:._-]+$");
            keys.add(key);
        }
        assertThat(keys).hasSize(cities.size());
    }

    @Test
    @DisplayName("AC-10: current and forecast keys carry plain coordinates, the UTC start date and the day count")
    void locationKeys() {
        assertThat(KEYS.current(SAO_PAULO)).isEqualTo("weather:current:v1:-23.5475:-46.63611");
        assertThat(KEYS.forecast(SAO_PAULO, LocalDate.of(2026, 11, 15), 3))
                .isEqualTo("weather:forecast:v1:-23.5475:-46.63611:2026-11-15:3");
        Location tiny = new Location("Null Island", "XX", 0.00001, -1.0E-7);
        assertThat(KEYS.current(tiny)).isEqualTo("weather:current:v1:0.000010:-0.00000010");
    }

    @Test
    @DisplayName("AC-6: no key contains a unit")
    void noUnitsInKeys() {
        assertThat(List.of(KEYS.current(SAO_PAULO), KEYS.forecast(SAO_PAULO, LocalDate.of(2026, 11, 15), 3),
                KEYS.geocoding("London")))
                .noneMatch(k -> k.contains("metric") || k.contains("imperial"));
    }
}
