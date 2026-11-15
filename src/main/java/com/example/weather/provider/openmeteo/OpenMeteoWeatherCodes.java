package com.example.weather.provider.openmeteo;

import static com.example.weather.weather.ConditionCode.CLEAR;
import static com.example.weather.weather.ConditionCode.CLOUDY;
import static com.example.weather.weather.ConditionCode.DRIZZLE;
import static com.example.weather.weather.ConditionCode.FOG;
import static com.example.weather.weather.ConditionCode.PARTLY_CLOUDY;
import static com.example.weather.weather.ConditionCode.RAIN;
import static com.example.weather.weather.ConditionCode.SNOW;
import static com.example.weather.weather.ConditionCode.THUNDERSTORM;

import com.example.weather.weather.Condition;
import com.example.weather.weather.ConditionCode;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Maps Open-Meteo WMO weather codes to the closed {@link ConditionCode} set (AC-11). */
public final class OpenMeteoWeatherCodes {

    private static final Map<Integer, Condition> CODES = Map.ofEntries(
            entry(0, CLEAR, "Clear sky"),
            entry(1, PARTLY_CLOUDY, "Mainly clear"),
            entry(2, PARTLY_CLOUDY, "Partly cloudy"),
            entry(3, CLOUDY, "Overcast"),
            entry(45, FOG, "Fog"),
            entry(48, FOG, "Depositing rime fog"),
            entry(51, DRIZZLE, "Light drizzle"),
            entry(53, DRIZZLE, "Moderate drizzle"),
            entry(55, DRIZZLE, "Dense drizzle"),
            entry(56, DRIZZLE, "Light freezing drizzle"),
            entry(57, DRIZZLE, "Dense freezing drizzle"),
            entry(61, RAIN, "Slight rain"),
            entry(63, RAIN, "Moderate rain"),
            entry(65, RAIN, "Heavy rain"),
            entry(66, RAIN, "Light freezing rain"),
            entry(67, RAIN, "Heavy freezing rain"),
            entry(80, RAIN, "Slight rain showers"),
            entry(81, RAIN, "Moderate rain showers"),
            entry(82, RAIN, "Violent rain showers"),
            entry(71, SNOW, "Slight snowfall"),
            entry(73, SNOW, "Moderate snowfall"),
            entry(75, SNOW, "Heavy snowfall"),
            entry(77, SNOW, "Snow grains"),
            entry(85, SNOW, "Slight snow showers"),
            entry(86, SNOW, "Heavy snow showers"),
            entry(95, THUNDERSTORM, "Thunderstorm"),
            entry(96, THUNDERSTORM, "Thunderstorm with slight hail"),
            entry(99, THUNDERSTORM, "Thunderstorm with heavy hail"));

    private OpenMeteoWeatherCodes() {}

    private static Map.Entry<Integer, Condition> entry(int code, ConditionCode condition, String description) {
        return Map.entry(code, new Condition(condition, description));
    }

    /** The condition for an Open-Meteo weather code, or empty if the code is not mapped. */
    public static Optional<Condition> condition(int code) {
        return Optional.ofNullable(CODES.get(code));
    }

    /** All mapped codes. */
    public static Set<Integer> codes() {
        return CODES.keySet();
    }
}
