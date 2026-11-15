package com.example.weather.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.weather.concurrent.BoundedFanOut;
import com.example.weather.provider.LocationNotFoundException;
import com.example.weather.provider.WeatherProvider;
import com.example.weather.provider.WeatherProviderUnavailableException;
import com.example.weather.provider.stub.StubWeatherProvider;
import com.example.weather.weather.CurrentConditions;
import com.example.weather.weather.CurrentWeather;
import com.example.weather.weather.Location;
import com.example.weather.weather.LocationForecast;
import com.example.weather.weather.ObservedConditions;
import com.example.weather.weather.Units;
import com.example.weather.weather.WeatherService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Unit test: mocked repository, a hand-written provider fake over the stub, a real
 * {@link WeatherService} with a fixed clock and a real {@link BoundedFanOut}. No Spring, no Docker.
 */
class FavouriteWeatherServiceTest {

    private static final String CLIENT = "client-1";
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00.789Z");
    private static final Instant NOW_SECONDS = Instant.parse("2026-11-15T10:00:00Z");
    private static final StubWeatherProvider STUB = new StubWeatherProvider();

    private final FavouriteRepository repository = mock(FavouriteRepository.class);
    private final FakeProvider provider = new FakeProvider();
    private final BoundedFanOut fanOut = BoundedFanOut.virtualThreads("fav-weather-test-");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(FavouriteWeatherService.class);
    private FavouriteWeatherService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        FavouriteService favourites = new FavouriteService(repository, provider,
                TransactionOperations.withoutTransaction(), clock, FavouritesProperties.defaults());
        service = new FavouriteWeatherService(favourites, new WeatherService(provider, clock), fanOut,
                new FavouritesWeatherProperties(2, Duration.ofSeconds(10)), clock);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        fanOut.close();
    }

    private static Favourite favourite(String city, String label) {
        Location location = STUB.locate(city);
        return new Favourite(UUID.randomUUID(), CLIENT, city, label, location, 1, NOW_SECONDS, NOW_SECONDS);
    }

    private List<ILoggingEvent> warnings() {
        return appender.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).toList();
    }

    @Test
    @DisplayName("AC-1/AC-2: items are in stored order with weather looked up by stored location at one instant")
    void happyPath() {
        List<Favourite> list = List.of(favourite("London", "Home"), favourite("Paris", null), favourite("Tokyo", null));
        when(repository.findAll(CLIENT)).thenReturn(list);

        List<FavouriteWeather> items = service.currentForAll(CLIENT, Units.IMPERIAL);

        assertThat(items).extracting(FavouriteWeather::favourite).containsExactlyElementsOf(list);
        assertThat(items).allSatisfy(item -> {
            assertThat(item.failure()).isNull();
            assertThat(item.weather().observedAt()).isEqualTo(NOW_SECONDS);
            assertThat(item.weather().units()).isEqualTo(Units.IMPERIAL);
            assertThat(item.weather().location()).isEqualTo(item.favourite().location());
        });
        CurrentWeather london = new WeatherService(STUB, Clock.fixed(NOW, ZoneOffset.UTC)).current("London", Units.IMPERIAL);
        assertThat(items.get(0).weather()).isEqualTo(london);
        assertThat(provider.locations).containsExactlyInAnyOrder(
                list.get(0).location(), list.get(1).location(), list.get(2).location());
        assertThat(provider.otherCalls).isEmpty();
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("AC-4: no favourites gives [] and no provider call")
    void emptyList() {
        when(repository.findAll(CLIENT)).thenReturn(List.of());

        assertThat(service.currentForAll(CLIENT, Units.METRIC)).isEmpty();
        assertThat(provider.locations).isEmpty();
        assertThat(provider.otherCalls).isEmpty();
    }

    @Test
    @DisplayName("AC-11/AC-14: expected failures become per-item failures; one WARN with counts only")
    void partialFailure() {
        List<Favourite> list = List.of(favourite("London", "LBL-SECRET"), favourite("Paris", null),
                favourite("Tokyo", null));
        when(repository.findAll(CLIENT)).thenReturn(list);
        provider.failAt(list.get(1).location(), () -> new WeatherProviderUnavailableException("down", null));
        provider.failAt(list.get(2).location(), () -> new LocationNotFoundException("Tokyo"));

        List<FavouriteWeather> items = service.currentForAll(CLIENT, Units.METRIC);

        assertThat(items.get(0).weather()).isNotNull();
        assertThat(items.get(1).failure()).isEqualTo(FavouriteWeatherFailure.UPSTREAM_UNAVAILABLE);
        assertThat(items.get(1).weather()).isNull();
        assertThat(items.get(2).failure()).isEqualTo(FavouriteWeatherFailure.LOCATION_NOT_FOUND);
        assertThat(warnings()).hasSize(1);
        String message = warnings().get(0).getFormattedMessage();
        assertThat(message).isEqualTo("Favourites weather partial failure failed=2 total=3 timedOut=0");
        assertThat(message).doesNotContain("London", "Paris", "Tokyo", "LBL-SECRET", CLIENT, "48.8566");
    }

    @Test
    @DisplayName("AC-12: every item failing is still a full list of failures")
    void allFail() {
        List<Favourite> list = List.of(favourite("London", null), favourite("Paris", null), favourite("Tokyo", null));
        when(repository.findAll(CLIENT)).thenReturn(list);
        list.forEach(f -> provider.failAt(f.location(), () -> new WeatherProviderUnavailableException("down", null)));

        List<FavouriteWeather> items = service.currentForAll(CLIENT, Units.METRIC);

        assertThat(items).hasSize(3)
                .allSatisfy(i -> assertThat(i.failure()).isEqualTo(FavouriteWeatherFailure.UPSTREAM_UNAVAILABLE));
        assertThat(warnings()).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("failed=3 total=3 timedOut=0"));
    }

    @Test
    @DisplayName("AC-15: an unexpected exception propagates unchanged")
    void bugPropagates() {
        List<Favourite> list = List.of(favourite("London", null), favourite("Paris", null));
        when(repository.findAll(CLIENT)).thenReturn(list);
        IllegalStateException bug = new IllegalStateException("bug");
        provider.failAt(list.get(1).location(), () -> bug);

        assertThatThrownBy(() -> service.currentForAll(CLIENT, Units.METRIC)).isSameAs(bug);
    }

    @Test
    @DisplayName("AC-15/AC-23: a repository failure propagates, the provider is untouched, only findAll is called")
    void repositoryFailure() {
        DataAccessResourceFailureException down = new DataAccessResourceFailureException("x");
        when(repository.findAll(CLIENT)).thenThrow(down);

        assertThatThrownBy(() -> service.currentForAll(CLIENT, Units.METRIC)).isSameAs(down);
        assertThat(provider.locations).isEmpty();
        assertThat(provider.otherCalls).isEmpty();
        verify(repository).findAll(CLIENT);
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("AC-23: a successful request only reads the favourites")
    void readOnly() {
        when(repository.findAll(CLIENT)).thenReturn(List.of(favourite("London", null)));

        service.currentForAll(CLIENT, Units.METRIC);

        verify(repository).findAll(CLIENT);
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("AC-17: an item that misses the time budget is UPSTREAM_UNAVAILABLE and counted as timed out")
    void timedOutItem() {
        FavouriteWeatherService quick = new FavouriteWeatherService(
                new FavouriteService(repository, provider, TransactionOperations.withoutTransaction(),
                        Clock.fixed(NOW, ZoneOffset.UTC), FavouritesProperties.defaults()),
                new WeatherService(provider, Clock.fixed(NOW, ZoneOffset.UTC)), fanOut,
                new FavouritesWeatherProperties(2, Duration.ofSeconds(1)), Clock.fixed(NOW, ZoneOffset.UTC));
        List<Favourite> list = List.of(favourite("London", null), favourite("Tokyo", null));
        when(repository.findAll(CLIENT)).thenReturn(list);
        provider.blockAt(list.get(1).location());

        List<FavouriteWeather> items = quick.currentForAll(CLIENT, Units.METRIC);

        assertThat(items.get(0).weather()).isNotNull();
        assertThat(items.get(1).failure()).isEqualTo(FavouriteWeatherFailure.UPSTREAM_UNAVAILABLE);
        assertThat(warnings()).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("failed=1 total=2 timedOut=1"));
    }

    /** Thread-safe provider fake: the stub's coordinate lookup, with per-location failures or blocking. */
    private static final class FakeProvider implements WeatherProvider {
        private final List<Location> locations = new CopyOnWriteArrayList<>();
        private final List<String> otherCalls = new CopyOnWriteArrayList<>();
        private final Map<Location, Supplier<RuntimeException>> failures = new ConcurrentHashMap<>();
        private final Map<Location, Boolean> blocked = new ConcurrentHashMap<>();

        void failAt(Location location, Supplier<RuntimeException> failure) {
            failures.put(location, failure);
        }

        void blockAt(Location location) {
            blocked.put(location, true);
        }

        @Override
        public ObservedConditions currentAtLocation(Location location, Instant now) {
            locations.add(location);
            Supplier<RuntimeException> failure = failures.get(location);
            if (failure != null) {
                throw failure.get();
            }
            if (blocked.containsKey(location)) {
                try {
                    new java.util.concurrent.CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    throw new WeatherProviderUnavailableException("interrupted", null);
                }
            }
            return STUB.currentAtLocation(location, now);
        }

        @Override
        public CurrentConditions current(String city) {
            otherCalls.add("current");
            return STUB.current(city);
        }

        @Override
        public ObservedConditions currentAt(String city, Instant now) {
            otherCalls.add("currentAt");
            return STUB.currentAt(city, now);
        }

        @Override
        public Location locate(String city) {
            otherCalls.add("locate");
            return STUB.locate(city);
        }

        @Override
        public LocationForecast forecast(String city, LocalDate startDate, int days) {
            otherCalls.add("forecast");
            return STUB.forecast(city, startDate, days);
        }
    }
}
