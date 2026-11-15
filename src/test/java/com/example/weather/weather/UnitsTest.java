package com.example.weather.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit test: wire values of {@link Units}. No Spring context, no Docker. */
class UnitsTest {

    @Test
    @DisplayName("AC-1/AC-2 (unit): wire values are lowercase metric/imperial")
    void wireValues() throws Exception {
        assertThat(Units.fromValue("metric")).isEqualTo(Units.METRIC);
        assertThat(Units.fromValue("imperial")).isEqualTo(Units.IMPERIAL);
        assertThat(new ObjectMapper().writeValueAsString(Units.IMPERIAL)).isEqualTo("\"imperial\"");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"METRIC", "Imperial", "kelvin"})
    @DisplayName("A-6 (unit): matching is case-sensitive and unknown values are rejected")
    void rejectsOtherValues(String value) {
        assertThatThrownBy(() -> Units.fromValue(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
