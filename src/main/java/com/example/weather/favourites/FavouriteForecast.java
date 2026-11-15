package com.example.weather.favourites;

import com.example.weather.weather.Forecast;
import java.util.Objects;

/**
 * A favourite with exactly one of its daily forecast or the reason it has none (ADR 0014 §2, 0016 §3).
 * {@link #toString()} delegates to {@link Favourite#toString()}, so it omits the client id and label.
 */
public record FavouriteForecast(Favourite favourite, Forecast forecast, FavouriteWeatherFailure failure) {

    public FavouriteForecast {
        Objects.requireNonNull(favourite, "favourite");
        if ((forecast == null) == (failure == null)) {
            throw new IllegalArgumentException("exactly one of forecast and failure must be set");
        }
    }

    public static FavouriteForecast ok(Favourite favourite, Forecast forecast) {
        return new FavouriteForecast(favourite, Objects.requireNonNull(forecast, "forecast"), null);
    }

    public static FavouriteForecast failed(Favourite favourite, FavouriteWeatherFailure failure) {
        return new FavouriteForecast(favourite, null, Objects.requireNonNull(failure, "failure"));
    }

    @Override
    public String toString() {
        return "FavouriteForecast[favourite=" + favourite + ", forecast=" + forecast + ", failure=" + failure + "]";
    }
}
