package com.example.weather.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** Unit test: no Spring context, no Docker. */
class UnitConverterTest {

    @Test
    void convertsCelsiusToFahrenheit() {
        assertThat(UnitConverter.celsiusToFahrenheit(0.0)).isEqualTo(32.0);
        assertThat(UnitConverter.celsiusToFahrenheit(100.0)).isEqualTo(212.0);
        assertThat(UnitConverter.celsiusToFahrenheit(-40.0)).isEqualTo(-40.0);
        assertThat(UnitConverter.celsiusToFahrenheit(11.2)).isCloseTo(11.2 * 9 / 5 + 32, within(0.05));
    }

    @Test
    void convertsMetresPerSecondToMph() {
        assertThat(UnitConverter.msToMph(0.0)).isEqualTo(0.0);
        assertThat(UnitConverter.msToMph(10.0)).isEqualTo(22.4);
        assertThat(UnitConverter.msToMph(4.1)).isEqualTo(9.2);
    }

    @Test
    void roundsHalfUpToOneDecimal() {
        assertThat(UnitConverter.round1(1.25)).isEqualTo(1.3);
        assertThat(UnitConverter.round1(1.24)).isEqualTo(1.2);
        assertThat(UnitConverter.round1(-1.25)).isEqualTo(-1.3);
        assertThat(UnitConverter.round1(52.16)).isEqualTo(52.2);
    }
}
