package com.example.weather.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.weather.Units;
import org.junit.jupiter.api.Test;

/** Unit test: no Spring context, no Docker. */
class QueryRecordsTest {

    @Test
    void currentQueryStripsCityAndDefaultsUnits() {
        CurrentWeatherQuery q = new CurrentWeatherQuery("  London ", null);
        assertThat(q.city()).isEqualTo("London");
        assertThat(q.units()).isEqualTo("metric");
        assertThat(q.resolvedUnits()).isEqualTo(Units.METRIC);
        assertThat(new CurrentWeatherQuery(null, "imperial").city()).isNull();
        assertThat(new CurrentWeatherQuery("x", "imperial").resolvedUnits()).isEqualTo(Units.IMPERIAL);
    }

    @Test
    void forecastQueryDefaultsDaysAndUnits() {
        ForecastQuery q = new ForecastQuery(" Paris", null, null);
        assertThat(q.city()).isEqualTo("Paris");
        assertThat(q.units()).isEqualTo("metric");
        assertThat(q.resolvedDays()).isEqualTo(5);
        assertThat(new ForecastQuery("Paris", "imperial", "").resolvedDays()).isEqualTo(5);
        assertThat(new ForecastQuery("Paris", "imperial", "3").resolvedDays()).isEqualTo(3);
        assertThat(new ForecastQuery("Paris", "imperial", " 7 ").resolvedDays()).isEqualTo(7);
    }
}
