package com.example.weather.favourites;

import com.example.weather.concurrent.BoundedFanOut;
import com.example.weather.provider.LocationNotFoundException;
import com.example.weather.provider.WeatherProviderUnavailableException;
import com.example.weather.weather.Units;
import com.example.weather.weather.WeatherService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Current weather for every favourite of a client (ADR 0012/0013/0014).
 *
 * <p>The favourites are loaded with one plain read before any provider call; no transaction or
 * connection is held during the fan-out, and nothing is written. Each favourite is looked up by its
 * stored location, at most {@code max-concurrency} at a time and within {@code timeout} overall.
 * Expected provider failures, and items that miss the time budget, become per-item failures;
 * anything else propagates (a bug is a {@code 500}, never a per-item error).
 *
 * <p>When at least one item failed, one WARN line with counts only is logged. Client ids, labels,
 * cities and coordinates are never logged.
 */
@Service
public class FavouriteWeatherService {

    private static final Logger log = LoggerFactory.getLogger(FavouriteWeatherService.class);

    private final FavouriteService favourites;
    private final WeatherService weather;
    private final BoundedFanOut fanOut;
    private final FavouritesWeatherProperties properties;
    private final Clock clock;

    public FavouriteWeatherService(FavouriteService favourites, WeatherService weather, BoundedFanOut fanOut,
                                   FavouritesWeatherProperties properties, Clock clock) {
        this.favourites = favourites;
        this.weather = weather;
        this.fanOut = fanOut;
        this.properties = properties;
        this.clock = clock;
    }

    /** The client's favourites in stored order, each with its weather or failure. */
    public List<FavouriteWeather> currentForAll(String clientId, Units units) {
        List<Favourite> list = favourites.list(clientId);
        if (list.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        List<Optional<FavouriteWeather>> results =
                fanOut.map(list, f -> lookup(f, units, now), properties.maxConcurrency(), properties.timeout());
        List<FavouriteWeather> items = new ArrayList<>(list.size());
        int failed = 0;
        int timedOut = 0;
        for (int i = 0; i < list.size(); i++) {
            FavouriteWeather item = results.get(i).orElse(null);
            if (item == null) {
                item = FavouriteWeather.failed(list.get(i), FavouriteWeatherFailure.UPSTREAM_UNAVAILABLE);
                timedOut++;
            }
            if (item.failure() != null) {
                failed++;
            }
            items.add(item);
        }
        if (failed > 0) {
            log.warn("Favourites weather partial failure failed={} total={} timedOut={}", failed, list.size(), timedOut);
        }
        return List.copyOf(items);
    }

    private FavouriteWeather lookup(Favourite favourite, Units units, Instant now) {
        try {
            return FavouriteWeather.ok(favourite, weather.currentAt(favourite.location(), units, now));
        } catch (WeatherProviderUnavailableException e) {
            return FavouriteWeather.failed(favourite, FavouriteWeatherFailure.UPSTREAM_UNAVAILABLE);
        } catch (LocationNotFoundException e) {
            return FavouriteWeather.failed(favourite, FavouriteWeatherFailure.LOCATION_NOT_FOUND);
        }
    }
}
