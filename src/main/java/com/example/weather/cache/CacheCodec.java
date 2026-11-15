package com.example.weather.cache;

import com.example.weather.weather.Condition;
import com.example.weather.weather.ConditionCode;
import com.example.weather.weather.CurrentConditions;
import com.example.weather.weather.DailyForecast;
import com.example.weather.weather.Location;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Versioned JSON values of the weather cache (ADR 0007 §5). A dedicated mapper reads into
 * package-private records holding only boxed scalars, strings and lists: no modules, no default
 * typing, no Java serialisation. Every decode validates the value completely and throws
 * {@link UnreadableCacheEntryException} (fixed reason, no value content) or a Jackson exception.
 */
final class CacheCodec {

    static final int VERSION = 1;

    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");

    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"v", "found", "name", "country", "latitude", "longitude"})
    record GeocodingValue(Integer v, Boolean found, String name, String country, Double latitude, Double longitude) {}

    @JsonPropertyOrder({"code", "description"})
    record ConditionValue(String code, String description) {}

    @JsonPropertyOrder({"v", "fetchedAt", "temperature", "feelsLike", "humidity", "windSpeed", "windDirection",
            "condition"})
    record CurrentValue(Integer v, String fetchedAt, Double temperature, Double feelsLike, Integer humidity,
                        Double windSpeed, Integer windDirection, ConditionValue condition) {}

    @JsonPropertyOrder({"date", "minTemperature", "maxTemperature", "precipitationProbability", "condition"})
    record DayValue(String date, Double minTemperature, Double maxTemperature, Integer precipitationProbability,
                    ConditionValue condition) {}

    @JsonPropertyOrder({"v", "days"})
    record ForecastValue(Integer v, List<DayValue> days) {}

    // ---------------------------------------------------------------- encode

    String encodeLocation(Location l) {
        return write(new GeocodingValue(VERSION, true, l.name(), l.country(), l.latitude(), l.longitude()));
    }

    String encodeNotFound() {
        return write(new GeocodingValue(VERSION, false, null, null, null, null));
    }

    String encodeCurrent(CurrentConditions c, Instant fetchedAt) {
        return write(new CurrentValue(VERSION, fetchedAt.toString(), c.temperature(), c.feelsLike(), c.humidity(),
                c.windSpeed(), c.windDirection(), condition(c.condition())));
    }

    String encodeForecast(List<DailyForecast> days) {
        List<DayValue> values = days.stream()
                .map(d -> new DayValue(d.date().toString(), d.minTemperature(), d.maxTemperature(),
                        d.precipitationProbability(), condition(d.condition())))
                .toList();
        return write(new ForecastValue(VERSION, values));
    }

    // ---------------------------------------------------------------- decode

    GeocodingEntry decodeGeocoding(String raw) throws JsonProcessingException {
        GeocodingValue v = read(raw, GeocodingValue.class);
        version(v.v());
        if (v.found() == null) {
            throw unreadable("missing found");
        }
        if (!v.found()) {
            return new GeocodingEntry.NotFound();
        }
        if (v.name() == null || v.name().isBlank()) {
            throw unreadable("missing name");
        }
        if (v.country() == null || !COUNTRY.matcher(v.country()).matches()) {
            throw unreadable("invalid country");
        }
        double latitude = range(v.latitude(), -90, 90, "invalid latitude");
        double longitude = range(v.longitude(), -180, 180, "invalid longitude");
        return new GeocodingEntry.Found(new Location(v.name(), v.country(), latitude, longitude));
    }

    CurrentEntry decodeCurrent(String raw, Location location) throws JsonProcessingException {
        CurrentValue v = read(raw, CurrentValue.class);
        version(v.v());
        Instant fetchedAt = instant(v.fetchedAt());
        CurrentConditions c = new CurrentConditions(
                location,
                finite(v.temperature(), "invalid temperature"),
                finite(v.feelsLike(), "invalid feelsLike"),
                intRange(v.humidity(), 0, 100, "invalid humidity"),
                range(v.windSpeed(), 0, Double.MAX_VALUE, "invalid windSpeed"),
                intRange(v.windDirection(), 0, 359, "invalid windDirection"),
                condition(v.condition()));
        return new CurrentEntry(c, fetchedAt);
    }

    List<DailyForecast> decodeForecast(String raw, LocalDate startDate, int days) throws JsonProcessingException {
        ForecastValue v = read(raw, ForecastValue.class);
        version(v.v());
        if (v.days() == null || v.days().size() != days) {
            throw unreadable("wrong number of days");
        }
        List<DailyForecast> result = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            DayValue d = v.days().get(i);
            if (d == null) {
                throw unreadable("missing day");
            }
            LocalDate date = date(d.date());
            if (!date.equals(startDate.plusDays(i))) {
                throw unreadable("dates mismatch");
            }
            result.add(new DailyForecast(
                    date,
                    finite(d.minTemperature(), "invalid minTemperature"),
                    finite(d.maxTemperature(), "invalid maxTemperature"),
                    intRange(d.precipitationProbability(), 0, 100, "invalid precipitationProbability"),
                    condition(d.condition())));
        }
        return List.copyOf(result);
    }

    // ---------------------------------------------------------------- helpers

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Our own records always serialise; failing here is a bug, not a cache failure.
            throw new IllegalStateException("Could not encode cache value", e);
        }
    }

    private <T> T read(String raw, Class<T> type) throws JsonProcessingException {
        T value = json.readValue(raw, type);
        if (value == null) {
            throw unreadable("null value");
        }
        return value;
    }

    private static ConditionValue condition(Condition c) {
        return new ConditionValue(c.code().name(), c.description());
    }

    private static Condition condition(ConditionValue v) {
        if (v == null || v.code() == null) {
            throw unreadable("missing condition");
        }
        ConditionCode code;
        try {
            code = ConditionCode.valueOf(v.code());
        } catch (IllegalArgumentException e) {
            throw unreadable("unknown condition code");
        }
        if (v.description() == null || v.description().isBlank()) {
            throw unreadable("missing condition description");
        }
        return new Condition(code, v.description());
    }

    private static void version(Integer v) {
        if (v == null || v != VERSION) {
            throw unreadable("unsupported version");
        }
    }

    private static double finite(Double value, String reason) {
        if (value == null || !Double.isFinite(value)) {
            throw unreadable(reason);
        }
        return value;
    }

    private static double range(Double value, double min, double max, String reason) {
        double v = finite(value, reason);
        if (v < min || v > max) {
            throw unreadable(reason);
        }
        return v;
    }

    private static int intRange(Integer value, int min, int max, String reason) {
        if (value == null || value < min || value > max) {
            throw unreadable(reason);
        }
        return value;
    }

    private static Instant instant(String value) {
        if (value == null) {
            throw unreadable("missing fetchedAt");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw unreadable("invalid fetchedAt");
        }
    }

    private static LocalDate date(String value) {
        if (value == null) {
            throw unreadable("missing date");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw unreadable("invalid date");
        }
    }

    private static UnreadableCacheEntryException unreadable(String reason) {
        return new UnreadableCacheEntryException(reason);
    }
}
