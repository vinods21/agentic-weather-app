package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.weather.provider.stub.StubWeatherProvider;
import com.example.weather.weather.Forecast;
import com.example.weather.weather.Location;
import com.example.weather.weather.Units;
import com.example.weather.weather.WeatherService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit test of the {@link FavouriteForecast} invariant and its redacted {@code toString}. */
class FavouriteForecastTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final StubWeatherProvider STUB = new StubWeatherProvider();
    private static final Location LONDON = STUB.locate("London");
    private static final Favourite FAVOURITE =
            new Favourite(UUID.randomUUID(), "cid-SECRET", "London", "LBL-SECRET", LONDON, 1, NOW, NOW);
    private static final Forecast FORECAST =
            new WeatherService(STUB, Clock.fixed(NOW, ZoneOffset.UTC)).forecast("London", Units.METRIC, 2);

    @Test
    @DisplayName("AC-14: exactly one of forecast and failure must be set")
    void exactlyOne() {
        assertThat(FavouriteForecast.ok(FAVOURITE, FORECAST).failure()).isNull();
        assertThat(FavouriteForecast.failed(FAVOURITE, FavouriteWeatherFailure.UPSTREAM_UNAVAILABLE).forecast())
                .isNull();
        assertThatThrownBy(() -> new FavouriteForecast(FAVOURITE, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FavouriteForecast(FAVOURITE, FORECAST, FavouriteWeatherFailure.LOCATION_NOT_FOUND))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FavouriteForecast(null, FORECAST, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> FavouriteForecast.ok(FAVOURITE, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> FavouriteForecast.failed(FAVOURITE, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("AC-14: toString omits the client id and the label")
    void redactedToString() {
        assertThat(FavouriteForecast.ok(FAVOURITE, FORECAST).toString())
                .doesNotContain("cid-SECRET", "LBL-SECRET");
        assertThat(FavouriteForecast.failed(FAVOURITE, FavouriteWeatherFailure.UPSTREAM_UNAVAILABLE).toString())
                .doesNotContain("cid-SECRET", "LBL-SECRET")
                .contains("UPSTREAM_UNAVAILABLE");
    }
}
