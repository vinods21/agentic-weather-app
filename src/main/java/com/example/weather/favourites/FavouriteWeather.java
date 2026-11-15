package com.example.weather.favourites;

import com.example.weather.weather.CurrentWeather;
import java.util.Objects;

/**
 * A favourite with exactly one of its current weather or the reason it has none (ADR 0014 §2).
 * {@link #toString()} delegates to {@link Favourite#toString()}, so it omits the client id and label.
 */
public record FavouriteWeather(Favourite favourite, CurrentWeather weather, FavouriteWeatherFailure failure) {

    public FavouriteWeather {
        Objects.requireNonNull(favourite, "favourite");
        if ((weather == null) == (failure == null)) {
            throw new IllegalArgumentException("exactly one of weather and failure must be set");
        }
    }

    public static FavouriteWeather ok(Favourite favourite, CurrentWeather weather) {
        return new FavouriteWeather(favourite, Objects.requireNonNull(weather, "weather"), null);
    }

    public static FavouriteWeather failed(Favourite favourite, FavouriteWeatherFailure failure) {
        return new FavouriteWeather(favourite, null, Objects.requireNonNull(failure, "failure"));
    }

    @Override
    public String toString() {
        return "FavouriteWeather[favourite=" + favourite + ", weather=" + weather + ", failure=" + failure + "]";
    }
}
