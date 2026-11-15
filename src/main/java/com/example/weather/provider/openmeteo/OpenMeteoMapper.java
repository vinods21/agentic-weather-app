package com.example.weather.provider.openmeteo;

import com.example.weather.provider.LocationNotFoundException;
import com.example.weather.weather.Condition;
import com.example.weather.weather.CurrentConditions;
import com.example.weather.weather.DailyForecast;
import com.example.weather.weather.Location;
import com.example.weather.weather.LocationForecast;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure mapping of Open-Meteo geocoding/forecast JSON to the domain model, with the
 * normalisation the v1 contract needs (AC-12..14, AC-18..20). Stateless and thread-safe.
 *
 * <p>An unusable geocoding result throws {@link LocationNotFoundException}; any other body that
 * can't be mapped throws {@link InvalidUpstreamResponseException} with a fixed reason.
 */
public final class OpenMeteoMapper {

    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");

    /** Maps {@code results[0]} of a geocoding response. */
    public Location toLocation(String city, JsonNode body) {
        JsonNode results = body == null ? null : body.get("results");
        if (results == null || !results.isArray() || results.isEmpty()) {
            throw new LocationNotFoundException(city);
        }
        JsonNode first = results.get(0);
        if (first == null || !first.isObject()) {
            throw invalid("invalid geocoding result");
        }
        JsonNode countryNode = first.get("country_code");
        if (countryNode == null || !countryNode.isTextual()) {
            throw new LocationNotFoundException(city);
        }
        String country = countryNode.asText().strip().toUpperCase(Locale.ROOT);
        if (!COUNTRY.matcher(country).matches()) {
            throw new LocationNotFoundException(city);
        }
        JsonNode nameNode = first.get("name");
        if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isBlank()) {
            throw invalid("missing geocoding name");
        }
        double latitude = number(first, "latitude", "missing geocoding latitude");
        double longitude = number(first, "longitude", "missing geocoding longitude");
        if (latitude < -90 || latitude > 90) {
            throw invalid("geocoding latitude out of range");
        }
        if (longitude < -180 || longitude > 180) {
            throw invalid("geocoding longitude out of range");
        }
        return new Location(nameNode.asText().strip(), country, latitude, longitude);
    }

    /** Maps the {@code current} block of a forecast response. */
    public CurrentConditions toCurrent(Location location, JsonNode body) {
        JsonNode current = body == null ? null : body.get("current");
        if (current == null || !current.isObject()) {
            throw invalid("missing current");
        }
        double temperature = number(current, "temperature_2m", "missing current.temperature_2m");
        double feelsLike = number(current, "apparent_temperature", "missing current.apparent_temperature");
        double humidity = number(current, "relative_humidity_2m", "missing current.relative_humidity_2m");
        double windSpeed = number(current, "wind_speed_10m", "missing current.wind_speed_10m");
        if (windSpeed < 0) {
            throw invalid("negative current.wind_speed_10m");
        }
        double windDirection = number(current, "wind_direction_10m", "missing current.wind_direction_10m");
        Condition condition = condition(current.get("weather_code"), "missing current.weather_code");
        return new CurrentConditions(location, temperature, feelsLike, percent(humidity), windSpeed,
                direction(windDirection), condition);
    }

    /** Maps the {@code daily} block of a forecast response; exactly {@code days} days from {@code start}. */
    public LocationForecast toDaily(Location location, JsonNode body, LocalDate start, int days) {
        JsonNode daily = body == null ? null : body.get("daily");
        if (daily == null || !daily.isObject()) {
            throw invalid("missing daily");
        }
        JsonNode time = array(daily, "time", days);
        JsonNode min = array(daily, "temperature_2m_min", days);
        JsonNode max = array(daily, "temperature_2m_max", days);
        JsonNode precipitation = array(daily, "precipitation_probability_max", days);
        JsonNode codes = array(daily, "weather_code", days);

        List<DailyForecast> result = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            LocalDate date = date(time.get(i));
            if (!date.equals(start.plusDays(i))) {
                throw invalid("daily dates mismatch");
            }
            double minTemperature = value(min.get(i), "missing daily.temperature_2m_min");
            double maxTemperature = value(max.get(i), "missing daily.temperature_2m_max");
            JsonNode p = precipitation.get(i);
            int precipitationProbability = p == null || p.isNull()
                    ? 0
                    : percent(value(p, "invalid daily.precipitation_probability_max"));
            Condition condition = condition(codes.get(i), "missing daily.weather_code");
            result.add(new DailyForecast(date, minTemperature, maxTemperature, precipitationProbability, condition));
        }
        return new LocationForecast(location, result);
    }

    static int percent(double value) {
        return (int) Math.max(0, Math.min(100, Math.round(value)));
    }

    static int direction(double value) {
        return (int) Math.floorMod(Math.round(value), 360L);
    }

    private static double number(JsonNode parent, String field, String reason) {
        return value(parent.get(field), reason);
    }

    private static double value(JsonNode node, String reason) {
        if (node == null || !node.isNumber()) {
            throw invalid(reason);
        }
        double v = node.doubleValue();
        if (!Double.isFinite(v)) {
            throw invalid(reason);
        }
        return v;
    }

    private static Condition condition(JsonNode node, String missingReason) {
        double v = value(node, missingReason);
        if (v != Math.rint(v) || v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw invalid("non-integral weather_code");
        }
        return OpenMeteoWeatherCodes.condition((int) v).orElseThrow(() -> invalid("unmapped weather_code"));
    }

    private static JsonNode array(JsonNode daily, String field, int days) {
        JsonNode node = daily.get(field);
        if (node == null || !node.isArray()) {
            throw invalid("missing daily array");
        }
        if (node.size() != days) {
            throw invalid("daily length mismatch");
        }
        return node;
    }

    private static LocalDate date(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw invalid("invalid daily.time");
        }
        try {
            return LocalDate.parse(node.asText());
        } catch (DateTimeParseException e) {
            throw invalid("invalid daily.time");
        }
    }

    private static InvalidUpstreamResponseException invalid(String reason) {
        return new InvalidUpstreamResponseException(reason);
    }
}
