package com.example.weather.provider.stub;

import static com.example.weather.weather.ConditionCode.CLEAR;
import static com.example.weather.weather.ConditionCode.CLOUDY;
import static com.example.weather.weather.ConditionCode.DRIZZLE;
import static com.example.weather.weather.ConditionCode.FOG;
import static com.example.weather.weather.ConditionCode.PARTLY_CLOUDY;
import static com.example.weather.weather.ConditionCode.RAIN;
import static com.example.weather.weather.ConditionCode.SNOW;
import static com.example.weather.weather.ConditionCode.THUNDERSTORM;

import com.example.weather.provider.LocationNotFoundException;
import com.example.weather.provider.WeatherProvider;
import com.example.weather.weather.Condition;
import com.example.weather.weather.ConditionCode;
import com.example.weather.weather.CurrentConditions;
import com.example.weather.weather.DailyForecast;
import com.example.weather.weather.Location;
import com.example.weather.weather.LocationForecast;
import com.example.weather.weather.ObservedConditions;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic in-process provider with fixed data for five cities. Opt-in for offline local
 * development ({@code WEATHER_PROVIDER=stub}) and the {@code it} profile (ADR 0005); created by
 * {@code WeatherProviderConfiguration}, not component-scanned. City lookup is case-insensitive;
 * any other city is "not found".
 */
public class StubWeatherProvider implements WeatherProvider {

    /** Number of forecast days the stub holds per city. */
    public static final int MAX_DAYS = 7;

    private record Day(double min, double max, int precipitation, Condition condition) {}

    private record CityData(CurrentConditions current, List<Day> days) {}

    private static final Map<String, CityData> CITIES = Map.of(
            "london", new CityData(
                    new CurrentConditions(new Location("London", "GB", 51.5074, -0.1278),
                            11.2, 9.8, 81, 4.1, 230, cond(CLOUDY, "Overcast clouds")),
                    List.of(
                            new Day(6.0, 12.5, 40, cond(RAIN, "Light rain")),
                            new Day(5.5, 11.0, 20, cond(PARTLY_CLOUDY, "Partly cloudy")),
                            new Day(4.0, 9.5, 10, cond(FOG, "Morning fog")),
                            new Day(3.5, 10.0, 60, cond(DRIZZLE, "Drizzle")),
                            new Day(5.0, 12.0, 80, cond(RAIN, "Moderate rain")),
                            new Day(6.5, 13.0, 30, cond(CLOUDY, "Overcast clouds")),
                            new Day(4.5, 11.5, 5, cond(CLEAR, "Clear sky")))),
            "paris", new CityData(
                    new CurrentConditions(new Location("Paris", "FR", 48.8566, 2.3522),
                            13.4, 12.6, 72, 3.2, 200, cond(PARTLY_CLOUDY, "Partly cloudy")),
                    List.of(
                            new Day(8.0, 14.5, 15, cond(PARTLY_CLOUDY, "Partly cloudy")),
                            new Day(7.5, 15.0, 5, cond(CLEAR, "Clear sky")),
                            new Day(9.0, 13.5, 70, cond(RAIN, "Light rain")),
                            new Day(8.5, 12.0, 90, cond(THUNDERSTORM, "Thunderstorms")),
                            new Day(6.0, 11.5, 40, cond(DRIZZLE, "Drizzle")),
                            new Day(5.5, 12.5, 10, cond(CLOUDY, "Overcast clouds")),
                            new Day(6.5, 14.0, 0, cond(CLEAR, "Clear sky")))),
            "new york", new CityData(
                    new CurrentConditions(new Location("New York", "US", 40.7128, -74.0060),
                            8.7, 5.9, 58, 6.3, 310, cond(CLEAR, "Clear sky")),
                    List.of(
                            new Day(3.0, 10.0, 0, cond(CLEAR, "Clear sky")),
                            new Day(2.5, 9.0, 10, cond(PARTLY_CLOUDY, "Partly cloudy")),
                            new Day(1.0, 6.5, 50, cond(RAIN, "Light rain")),
                            new Day(-1.5, 3.0, 70, cond(SNOW, "Light snow")),
                            new Day(-2.0, 2.5, 30, cond(CLOUDY, "Overcast clouds")),
                            new Day(0.0, 5.5, 10, cond(PARTLY_CLOUDY, "Partly cloudy")),
                            new Day(1.5, 8.0, 0, cond(CLEAR, "Clear sky")))),
            "tokyo", new CityData(
                    new CurrentConditions(new Location("Tokyo", "JP", 35.6762, 139.6503),
                            16.1, 15.8, 65, 2.4, 90, cond(CLOUDY, "Overcast clouds")),
                    List.of(
                            new Day(11.0, 17.5, 20, cond(CLOUDY, "Overcast clouds")),
                            new Day(12.0, 18.0, 60, cond(RAIN, "Light rain")),
                            new Day(10.5, 16.0, 85, cond(RAIN, "Heavy rain")),
                            new Day(9.0, 15.5, 10, cond(PARTLY_CLOUDY, "Partly cloudy")),
                            new Day(8.5, 16.5, 0, cond(CLEAR, "Clear sky")),
                            new Day(9.5, 17.0, 5, cond(CLEAR, "Clear sky")),
                            new Day(10.0, 16.0, 30, cond(FOG, "Morning fog")))),
            "sydney", new CityData(
                    new CurrentConditions(new Location("Sydney", "AU", -33.8688, 151.2093),
                            24.3, 25.1, 60, 5.6, 135, cond(PARTLY_CLOUDY, "Partly cloudy")),
                    List.of(
                            new Day(18.0, 26.5, 10, cond(PARTLY_CLOUDY, "Partly cloudy")),
                            new Day(19.0, 28.0, 0, cond(CLEAR, "Clear sky")),
                            new Day(20.5, 30.0, 40, cond(THUNDERSTORM, "Afternoon thunderstorms")),
                            new Day(17.5, 24.0, 70, cond(RAIN, "Showers")),
                            new Day(16.0, 23.5, 20, cond(CLOUDY, "Overcast clouds")),
                            new Day(17.0, 25.0, 5, cond(CLEAR, "Clear sky")),
                            new Day(18.5, 27.0, 15, cond(PARTLY_CLOUDY, "Partly cloudy")))));

    private static Condition cond(ConditionCode code, String description) {
        return new Condition(code, description);
    }

    @Override
    public CurrentConditions current(String city) {
        return lookup(city).current();
    }

    /** The city's fixed location, without building conditions (ADR 0011 §3). */
    @Override
    public Location locate(String city) {
        return lookup(city).current().location();
    }

    /**
     * The fixed conditions of the city whose location has exactly these coordinates, observed at
     * {@code now} (ADR 0012 §4). Name and country are ignored; other coordinates are "not found".
     */
    @Override
    public ObservedConditions currentAtLocation(Location location, Instant now) {
        CityData data = atCoordinates(location).orElseThrow(() -> new LocationNotFoundException(location.name()));
        return new ObservedConditions(data.current(), now);
    }

    @Override
    public LocationForecast forecast(String city, LocalDate startDate, int days) {
        CityData data = lookup(city);
        checkDays(days);
        return new LocationForecast(data.current().location(), firstDays(data, startDate, days));
    }

    /**
     * The fixed days of the city whose location has exactly these coordinates, dated from
     * {@code startDate}, with the passed-in location (ADR 0015 §4). Other coordinates are "not found".
     */
    @Override
    public LocationForecast forecastAtLocation(Location location, LocalDate startDate, int days) {
        checkDays(days);
        CityData data = atCoordinates(location).orElseThrow(() -> new LocationNotFoundException(location.name()));
        return new LocationForecast(location, firstDays(data, startDate, days));
    }

    private static Optional<CityData> atCoordinates(Location location) {
        for (CityData data : CITIES.values()) {
            Location fixed = data.current().location();
            if (fixed.latitude() == location.latitude() && fixed.longitude() == location.longitude()) {
                return Optional.of(data);
            }
        }
        return Optional.empty();
    }

    private static void checkDays(int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new IllegalArgumentException("days must be between 1 and " + MAX_DAYS);
        }
    }

    private static List<DailyForecast> firstDays(CityData data, LocalDate startDate, int days) {
        List<DailyForecast> result = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            Day d = data.days().get(i);
            result.add(new DailyForecast(startDate.plusDays(i), d.min(), d.max(), d.precipitation(), d.condition()));
        }
        return result;
    }

    private static CityData lookup(String city) {
        CityData data = city == null ? null : CITIES.get(city.strip().toLowerCase(Locale.ROOT));
        if (data == null) {
            throw new LocationNotFoundException(city);
        }
        return data;
    }
}
