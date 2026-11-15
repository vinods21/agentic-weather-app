package com.example.weather.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.weather.weather.Condition;
import com.example.weather.weather.ConditionCode;
import com.example.weather.weather.CurrentConditions;
import com.example.weather.weather.DailyForecast;
import com.example.weather.weather.Location;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit test: no Spring context, no Docker. */
class CacheCodecTest {

    static final Location LONDON = new Location("London", "GB", 51.50853, -0.12574);
    static final LocalDate START = LocalDate.of(2026, 11, 15);
    static final Instant FETCHED_AT = Instant.parse("2026-11-15T10:00:00Z");
    static final CurrentConditions CURRENT =
            new CurrentConditions(LONDON, 11.2, 9.8, 81, 4.1, 230, new Condition(ConditionCode.CLOUDY, "Overcast"));
    static final List<DailyForecast> DAYS = List.of(
            new DailyForecast(START, 6.0, 12.5, 40, new Condition(ConditionCode.RAIN, "Slight rain")),
            new DailyForecast(START.plusDays(1), -3.25, 0.1, 0, new Condition(ConditionCode.SNOW, "Snow")),
            new DailyForecast(START.plusDays(2), 1.0E-7, 100.0, 100, new Condition(ConditionCode.CLEAR, "Clear")));

    private final CacheCodec codec = new CacheCodec();

    @Test
    @DisplayName("AC-11: values are versioned JSON with the documented shape")
    void exactShape() {
        assertThat(codec.encodeLocation(LONDON)).isEqualTo(
                "{\"v\":1,\"found\":true,\"name\":\"London\",\"country\":\"GB\",\"latitude\":51.50853,\"longitude\":-0.12574}");
        assertThat(codec.encodeNotFound()).isEqualTo("{\"v\":1,\"found\":false}");
        assertThat(codec.encodeCurrent(CURRENT, FETCHED_AT)).isEqualTo(
                "{\"v\":1,\"fetchedAt\":\"2026-11-15T10:00:00Z\",\"temperature\":11.2,\"feelsLike\":9.8,\"humidity\":81,"
                        + "\"windSpeed\":4.1,\"windDirection\":230,\"condition\":{\"code\":\"CLOUDY\",\"description\":\"Overcast\"}}");
        assertThat(codec.encodeForecast(DAYS.subList(0, 1))).isEqualTo(
                "{\"v\":1,\"days\":[{\"date\":\"2026-11-15\",\"minTemperature\":6.0,\"maxTemperature\":12.5,"
                        + "\"precipitationProbability\":40,\"condition\":{\"code\":\"RAIN\",\"description\":\"Slight rain\"}}]}");
    }

    @Test
    @DisplayName("AC-11: every kind round-trips exactly, including non-ASCII and negative coordinates")
    void roundTrips() throws Exception {
        Location saoPaulo = new Location("São Paulo", "BR", -23.5475, -46.63611);
        assertThat(codec.decodeGeocoding(codec.encodeLocation(saoPaulo))).isEqualTo(new GeocodingEntry.Found(saoPaulo));
        assertThat(codec.decodeGeocoding(codec.encodeNotFound())).isEqualTo(new GeocodingEntry.NotFound());

        CurrentConditions nonAscii = new CurrentConditions(saoPaulo, 0.1 + 0.2, -9.8, 0, 0.0, 359,
                new Condition(ConditionCode.FOG, "Névoa – 霧"));
        assertThat(codec.decodeCurrent(codec.encodeCurrent(nonAscii, FETCHED_AT), saoPaulo))
                .isEqualTo(new CurrentEntry(nonAscii, FETCHED_AT));

        assertThat(codec.decodeForecast(codec.encodeForecast(DAYS), START, 3)).isEqualTo(DAYS);
    }

    @Test
    @DisplayName("AC-23: unknown extra fields are ignored")
    void unknownFieldsTolerated() throws Exception {
        assertThat(codec.decodeGeocoding("{\"v\":1,\"found\":false,\"extra\":[1,2]}"))
                .isEqualTo(new GeocodingEntry.NotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not-json",
        "{}",
        "null",
        "[]",
        "{\"v\":1,\"found\":false} trailing",
        "{\"v\":0,\"found\":false}",
        "{\"v\":2,\"found\":false}",
        "{\"v\":\"1\",\"found\":false}",
        "{\"v\":1,\"found\":\"false\"}",
        "{\"v\":1,\"found\":true,\"name\":\"London\",\"country\":\"gb\",\"latitude\":51.5,\"longitude\":-0.1}",
        "{\"v\":1,\"found\":true,\"name\":\" \",\"country\":\"GB\",\"latitude\":51.5,\"longitude\":-0.1}",
        "{\"v\":1,\"found\":true,\"name\":\"London\",\"country\":\"GB\",\"latitude\":91,\"longitude\":-0.1}",
        "{\"v\":1,\"found\":true,\"name\":\"London\",\"country\":\"GB\",\"latitude\":51.5}",
        "{\"v\":1,\"found\":true,\"name\":\"London\",\"country\":\"GB\",\"latitude\":\"51.5\",\"longitude\":-0.1}",
    })
    @DisplayName("AC-23: malformed, foreign-version or invalid geocoding values are rejected")
    void rejectsGeocoding(String raw) {
        assertThatThrownBy(() -> codec.decodeGeocoding(raw)).isInstanceOfAny(
                JacksonException.class, UnreadableCacheEntryException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"humidity\":101}",
        "{\"humidity\":81.5}",
        "{\"humidity\":\"81\"}",
        "{\"windDirection\":360}",
        "{\"windSpeed\":-1}",
        "{\"temperature\":null}",
        "{\"fetchedAt\":\"yesterday\"}",
        "{\"condition\":{\"code\":\"HAIL\",\"description\":\"Hail\"}}",
        "{\"condition\":{\"code\":\"CLOUDY\",\"description\":\"\"}}",
        "{\"v\":0}",
    })
    @DisplayName("AC-23: a current value with a bad field is rejected")
    void rejectsCurrent(String override) {
        String raw = merge(codec.encodeCurrent(CURRENT, FETCHED_AT), override);
        assertThatThrownBy(() -> codec.decodeCurrent(raw, LONDON)).isInstanceOfAny(
                JacksonException.class, UnreadableCacheEntryException.class);
    }

    @Test
    @DisplayName("AC-23: a forecast with the wrong number of days or non-consecutive dates is rejected")
    void rejectsForecastShape() {
        String three = codec.encodeForecast(DAYS);
        assertThatThrownBy(() -> codec.decodeForecast(three, START, 2)).isInstanceOf(UnreadableCacheEntryException.class);
        assertThatThrownBy(() -> codec.decodeForecast(three, START.plusDays(1), 3))
                .isInstanceOf(UnreadableCacheEntryException.class);
        String gap = codec.encodeForecast(List.of(DAYS.get(0), DAYS.get(2)));
        assertThatThrownBy(() -> codec.decodeForecast(gap, START, 2)).isInstanceOf(UnreadableCacheEntryException.class);
        assertThatThrownBy(() -> codec.decodeForecast("{\"v\":1,\"days\":[null]}", START, 1))
                .isInstanceOf(UnreadableCacheEntryException.class);
        assertThatThrownBy(() -> codec.decodeForecast(
                three.replace("\"precipitationProbability\":40", "\"precipitationProbability\":-1"), START, 3))
                .isInstanceOf(UnreadableCacheEntryException.class);
    }

    @Test
    @DisplayName("AC-23: the rejection reason never contains value content")
    void reasonHasNoValueContent() {
        assertThatThrownBy(() -> codec.decodeGeocoding(
                "{\"v\":1,\"found\":true,\"name\":\"Secretville\",\"country\":\"zz\",\"latitude\":1,\"longitude\":1}"))
                .isInstanceOf(UnreadableCacheEntryException.class)
                .message().doesNotContain("Secretville").doesNotContain("zz");
    }

    /** Replaces/adds the top-level fields of {@code override} in {@code base} (both flat-ish JSON objects). */
    private static String merge(String base, String override) {
        try {
            ObjectMapper m = new ObjectMapper();
            ObjectNode node = (ObjectNode) m.readTree(base);
            node.setAll((ObjectNode) m.readTree(override));
            return m.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
