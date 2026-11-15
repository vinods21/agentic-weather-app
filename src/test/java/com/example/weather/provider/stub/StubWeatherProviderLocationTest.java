package com.example.weather.provider.stub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.weather.provider.LocationNotFoundException;
import com.example.weather.weather.Location;
import com.example.weather.weather.ObservedConditions;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit test: {@link StubWeatherProvider#currentAtLocation} matches exact coordinates (ADR 0012 §4). */
class StubWeatherProviderLocationTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    private final StubWeatherProvider stub = new StubWeatherProvider();

    @ParameterizedTest
    @ValueSource(strings = {"London", "Paris", "New York", "Tokyo", "Sydney"})
    @DisplayName("AC-1: each fixed location gives the city's conditions observed at now")
    void knownLocations(String city) {
        Location location = stub.locate(city);

        ObservedConditions observed = stub.currentAtLocation(location, NOW);

        assertThat(observed.observedAt()).isEqualTo(NOW);
        assertThat(observed.conditions()).isEqualTo(stub.current(city));
    }

    @Test
    @DisplayName("AC-1: name and country are ignored, only the coordinates decide")
    void nameIgnored() {
        Location paris = stub.locate("Paris");
        Location renamed = new Location("Somewhere", "XX", paris.latitude(), paris.longitude());

        assertThat(stub.currentAtLocation(renamed, NOW).conditions()).isEqualTo(stub.current("Paris"));
    }

    @Test
    @DisplayName("AC-14: a known name with other coordinates, or unknown coordinates, is not found")
    void unknownCoordinates() {
        Location london = stub.locate("London");
        assertThatThrownBy(() -> stub.currentAtLocation(
                new Location("London", "GB", london.latitude() + 0.0001, london.longitude()), NOW))
                .isInstanceOf(LocationNotFoundException.class);
        assertThatThrownBy(() -> stub.currentAtLocation(new Location("Nowhere", "XX", 0.0, 0.0), NOW))
                .isInstanceOf(LocationNotFoundException.class);
    }
}
