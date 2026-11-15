package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.weather.concurrent.BoundedFanOut;
import com.example.weather.provider.LocationNotFoundException;
import com.example.weather.provider.WeatherProviderUnavailableException;
import com.example.weather.provider.stub.StubWeatherProvider;
import com.example.weather.weather.CurrentWeather;
import com.example.weather.weather.Location;
import com.example.weather.weather.ObservedConditions;
import com.example.weather.weather.Units;
import com.example.weather.weather.WeatherService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Tester-added edge cases for {@link FavouriteWeather} and {@link FavouriteWeatherService} that the
 * developer's tests don't cover. No Spring, no Docker.
 */
class FavouriteWeatherEdgeCasesTest {

    private static final String CLIENT = "client-SECRET";
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final FavouriteRepository repository = mock(FavouriteRepository.class);
    private final RecordingStub provider = new RecordingStub();
    private final BoundedFanOut fanOut = BoundedFanOut.virtualThreads("fav-weather-edge-");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(FavouriteWeatherService.class);

    /** The stub, recording the locations asked for, with per-latitude failures and blocking. */
    private static final class RecordingStub extends StubWeatherProvider {
        final List<Location> asked = new CopyOnWriteArrayList<>();
        final Set<Double> unavailable = ConcurrentHashMap.newKeySet();
        final Set<Double> blocked = ConcurrentHashMap.newKeySet();

        @Override
        public ObservedConditions currentAtLocation(Location location, Instant now) {
            asked.add(location);
            if (unavailable.contains(location.latitude())) {
                throw new WeatherProviderUnavailableException("down", null);
            }
            if (blocked.contains(location.latitude())) {
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    throw new WeatherProviderUnavailableException("interrupted", null);
                }
            }
            return super.currentAtLocation(location, now);
        }
    }

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        fanOut.close();
    }

    private FavouriteWeatherService service(int maxConcurrency, Duration timeout) {
        FavouriteService favourites = new FavouriteService(repository, provider,
                TransactionOperations.withoutTransaction(), CLOCK, FavouritesProperties.defaults());
        return new FavouriteWeatherService(favourites, new WeatherService(provider, CLOCK), fanOut,
                new FavouritesWeatherProperties(maxConcurrency, timeout), CLOCK);
    }

    private static Favourite favourite(String city, String label, Location location) {
        return new Favourite(UUID.randomUUID(), CLIENT, city, label, location, 1, NOW, NOW);
    }

    private static Location stubLocation(String city) {
        return new StubWeatherProvider().locate(city);
    }

    private List<String> warnings() {
        return appender.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    // ---------------------------------------------------------------- FavouriteWeather

    @Test
    @DisplayName("AC-19 (edge): a FavouriteWeather with both weather and failure, or neither, is rejected; a null favourite is rejected")
    void exactlyOneOfWeatherOrFailure() {
        Favourite f = favourite("London", null, stubLocation("London"));
        CurrentWeather w = new WeatherService(new StubWeatherProvider(), CLOCK).current("London", Units.METRIC);

        assertThatThrownBy(() -> new FavouriteWeather(f, w, FavouriteWeatherFailure.UPSTREAM_UNAVAILABLE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FavouriteWeather(f, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FavouriteWeather.ok(f, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> FavouriteWeather.failed(f, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> FavouriteWeather.ok(null, w)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("AC-21 (edge): FavouriteWeather#toString never prints the client id or the label")
    void toStringRedactsClientIdAndLabel() {
        Favourite f = favourite("London", "LBL-SECRET", stubLocation("London"));

        assertThat(FavouriteWeather.failed(f, FavouriteWeatherFailure.LOCATION_NOT_FOUND).toString())
                .doesNotContain(CLIENT).doesNotContain("LBL-SECRET").contains("LOCATION_NOT_FOUND");
    }

    // ---------------------------------------------------------------- service

    @Test
    @DisplayName("A-7 (edge): two favourites with identical stored coordinates are each looked up and both get weather, in order")
    void duplicateCoordinatesAreLookedUpPerItem() {
        Location london = stubLocation("London");
        List<Favourite> list = List.of(favourite("London", null, london), favourite("Londres", "Work", london));
        when(repository.findAll(CLIENT)).thenReturn(list);

        List<FavouriteWeather> items = service(4, Duration.ofSeconds(5)).currentForAll(CLIENT, Units.METRIC);

        assertThat(items).extracting(FavouriteWeather::favourite).containsExactlyElementsOf(list);
        assertThat(items).allSatisfy(i -> assertThat(i.weather()).isNotNull());
        assertThat(provider.asked).containsExactly(london, london);
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("AC-11/AC-17 (edge): a provider failure, a not-found and a timed-out item are all counted in failed; only the timeout in timedOut")
    void mixedFailureCounts() {
        List<Favourite> list = List.of(
                favourite("London", null, stubLocation("London")),
                favourite("Paris", null, stubLocation("Paris")),
                favourite("Atlantis", null, new Location("Atlantis", "XX", 0.0, 0.0)),
                favourite("Tokyo", null, stubLocation("Tokyo")));
        when(repository.findAll(CLIENT)).thenReturn(list);
        provider.unavailable.add(stubLocation("Paris").latitude());
        provider.blocked.add(stubLocation("Tokyo").latitude());

        List<FavouriteWeather> items = service(4, Duration.ofSeconds(1)).currentForAll(CLIENT, Units.METRIC);

        assertThat(items).extracting(FavouriteWeather::failure).containsExactly(null,
                FavouriteWeatherFailure.UPSTREAM_UNAVAILABLE, FavouriteWeatherFailure.LOCATION_NOT_FOUND,
                FavouriteWeatherFailure.UPSTREAM_UNAVAILABLE);
        assertThat(warnings()).containsExactly("Favourites weather partial failure failed=3 total=4 timedOut=1");
    }

    @Test
    @DisplayName("AC-14 (edge): the stub treats non-finite stored coordinates as not found (per-item error, not a 500)")
    void nonFiniteCoordinatesWithStubAreNotFound() {
        List<Favourite> list = List.of(favourite("Nowhere", null, new Location("Nowhere", "XX", Double.NaN, Double.NaN)),
                favourite("Sydney", null, stubLocation("Sydney")));
        when(repository.findAll(CLIENT)).thenReturn(list);

        List<FavouriteWeather> items = service(4, Duration.ofSeconds(5)).currentForAll(CLIENT, Units.METRIC);

        assertThat(items.get(0).failure()).isEqualTo(FavouriteWeatherFailure.LOCATION_NOT_FOUND);
        assertThat(items.get(1).weather()).isNotNull();
    }

    @Test
    @DisplayName("AC-16 (edge): max-concurrency=1 still answers every item in order (serial fan-out)")
    void serialFanOut() {
        List<Favourite> list = List.of(favourite("Sydney", null, stubLocation("Sydney")),
                favourite("New York", null, stubLocation("New York")),
                favourite("Tokyo", null, stubLocation("Tokyo")));
        when(repository.findAll(CLIENT)).thenReturn(list);

        List<FavouriteWeather> items = service(1, Duration.ofSeconds(5)).currentForAll(CLIENT, Units.IMPERIAL);

        assertThat(items).extracting(i -> i.weather().location().name())
                .containsExactly("Sydney", "New York", "Tokyo");
        assertThat(items).allSatisfy(i -> assertThat(i.weather().units()).isEqualTo(Units.IMPERIAL));
    }

    @Test
    @DisplayName("AC-14 (edge): a LocationNotFoundException (which carries the city) never puts the city into the WARN line")
    void notFoundCityNotLogged() {
        when(repository.findAll(CLIENT)).thenReturn(
                List.of(favourite("Atlantis-SECRET", "LBL-SECRET", new Location("Atlantis-SECRET", "XX", 1.0, 2.0))));

        List<FavouriteWeather> items = service(4, Duration.ofSeconds(5)).currentForAll(CLIENT, Units.METRIC);

        assertThat(items.get(0).failure()).isEqualTo(FavouriteWeatherFailure.LOCATION_NOT_FOUND);
        assertThat(warnings()).singleElement().asString()
                .doesNotContain("Atlantis").doesNotContain("LBL-SECRET").doesNotContain(CLIENT)
                .doesNotContain("1.0").doesNotContain("2.0");
        assertThat(new LocationNotFoundException("Atlantis-SECRET").city()).isEqualTo("Atlantis-SECRET");
    }
}
