package com.example.weather.favourites;

import com.example.weather.concurrent.BoundedFanOut;
import com.example.weather.provider.LocationNotFoundException;
import com.example.weather.provider.WeatherProviderUnavailableException;
import com.example.weather.weather.Units;
import com.example.weather.weather.WeatherService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Daily forecast for every favourite of a client (ADR 0013/0014/0015/0016).
 *
 * <p>The favourites are loaded with one plain read before any provider call; no transaction or
 * connection is held during the fan-out, and nothing is written. The UTC start date is read once
 * per request, so every item covers the same dates. Each favourite is looked up by its stored
 * location through the shared {@link BoundedFanOut} bean, under the shared
 * {@code weather.favourites.weather.*} limits. Expected provider failures, and items that miss the
 * time budget, become per-item failures; anything else propagates (a bug is a {@code 500}).
 *
 * <p>When at least one item failed, one WARN line with counts only is logged. Client ids, labels,
 * cities and coordinates are never logged.
 */
@Service
public class FavouriteForecastService {

    private static final Logger log = LoggerFactory.getLogger(FavouriteForecastService.class);

    private final FavouriteService favourites;
    private final WeatherService weather;
    private final BoundedFanOut fanOut;
    private final FavouritesWeatherProperties properties;
    private final Clock clock;

    public FavouriteForecastService(FavouriteService favourites, WeatherService weather, BoundedFanOut fanOut,
                                    FavouritesWeatherProperties properties, Clock clock) {
        this.favourites = favourites;
        this.weather = weather;
        this.fanOut = fanOut;
        this.properties = properties;
        this.clock = clock;
    }

    /** The client's favourites in stored order, each with its forecast of {@code days} days or a failure. */
    public List<FavouriteForecast> forecastForAll(String clientId, Units units, int days) {
        List<Favourite> list = favourites.list(clientId);
        if (list.isEmpty()) {
            return List.of();
        }
        LocalDate start = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        List<Optional<FavouriteForecast>> results = fanOut.map(
                list, f -> lookup(f, units, start, days), properties.maxConcurrency(), properties.timeout());
        List<FavouriteForecast> items = new ArrayList<>(list.size());
        int failed = 0;
        int timedOut = 0;
        for (int i = 0; i < list.size(); i++) {
            FavouriteForecast item = results.get(i).orElse(null);
            if (item == null) {
                item = FavouriteForecast.failed(list.get(i), FavouriteWeatherFailure.UPSTREAM_UNAVAILABLE);
                timedOut++;
            }
            if (item.failure() != null) {
                failed++;
            }
            items.add(item);
        }
        if (failed > 0) {
            log.warn("Favourites forecast partial failure failed={} total={} timedOut={}", failed, list.size(), timedOut);
        }
        return List.copyOf(items);
    }

    private FavouriteForecast lookup(Favourite favourite, Units units, LocalDate start, int days) {
        try {
            return FavouriteForecast.ok(favourite, weather.forecastAt(favourite.location(), units, start, days));
        } catch (WeatherProviderUnavailableException e) {
            return FavouriteForecast.failed(favourite, FavouriteWeatherFailure.UPSTREAM_UNAVAILABLE);
        } catch (LocationNotFoundException e) {
            return FavouriteForecast.failed(favourite, FavouriteWeatherFailure.LOCATION_NOT_FOUND);
        }
    }
}
