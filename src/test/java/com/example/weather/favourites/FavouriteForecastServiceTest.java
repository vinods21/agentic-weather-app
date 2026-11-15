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
import com.example.weather.weather.Forecast;
import com.example.weather.weather.Location;
import com.example.weather.weather.LocationForecast;
import com.example.weather.weather.ObservedConditions;
import com.example.weather.weather.Units;
import com.example.weather.weather.WeatherService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Unit test of {@link FavouriteForecastService}: mocked repository, a hand-written provider fake over
 * the stub, a real {@link WeatherService} and a real {@link BoundedFanOut}. No Spring, no Docker.
 */
class FavouriteForecastServiceTest {

    private static final String CLIENT = "client-1";
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final LocalDate START = LocalDate.of(2026, 11, 15);
    private static final StubWeatherProvider STUB = new StubWeatherProvider();

    private final FavouriteRepository repository = mock(FavouriteRepository.class);
    private final FakeProvider provider = new FakeProvider();
    private final BoundedFanOut fanOut = BoundedFanOut.virtualThreads("fav-forecast-test-");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(FavouriteForecastService.class);
    private final CountingClock serviceClock = new CountingClock(NOW, NOW);
    private FavouriteForecastService service;

    @BeforeEach
    void setUp() {
        service = service(serviceClock, 2, Duration.ofSeconds(10));
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        fanOut.close();
    }

    private FavouriteForecastService service(Clock clock, int maxConcurrency, Duration timeout) {
        Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);
        FavouriteService favourites = new FavouriteService(repository, provider,
                TransactionOperations.withoutTransaction(), fixed, FavouritesProperties.defaults());
        return new FavouriteForecastService(favourites, new WeatherService(provider, fixed), fanOut,
                new FavouritesWeatherProperties(maxConcurrency, timeout), clock);
    }

    private static Favourite favourite(String city, String label) {
        Location location = STUB.locate(city);
        return new Favourite(UUID.randomUUID(), CLIENT, city, label, location, 1, NOW, NOW);
    }

    private List<ILoggingEvent> warnings() {
        return appender.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).toList();
    }

    @Test
    @DisplayName("AC-1: items are in stored order, each with the forecast at its stored location")
    void happyPath() {
        List<Favourite> list = List.of(favourite("London", "Home"), favourite("Paris", null), favourite("Tokyo", null));
        when(repository.findAll(CLIENT)).thenReturn(list);

        List<FavouriteForecast> items = service.forecastForAll(CLIENT, Units.IMPERIAL, 3);

        assertThat(items).extracting(FavouriteForecast::favourite).containsExactlyElementsOf(list);
        WeatherService byCity = new WeatherService(STUB, Clock.fixed(NOW, ZoneOffset.UTC));
        for (FavouriteForecast item : items) {
            assertThat(item.failure()).isNull();
            assertThat(item.forecast()).isEqualTo(byCity.forecast(item.favourite().city(), Units.IMPERIAL, 3));
            assertThat(item.forecast().location()).isEqualTo(item.favourite().location());
        }
        assertThat(provider.calls).extracting(Call::location).containsExactlyInAnyOrder(
                list.get(0).location(), list.get(1).location(), list.get(2).location());
        assertThat(provider.calls).allSatisfy(c -> {
            assertThat(c.startDate()).isEqualTo(START);
            assertThat(c.days()).isEqualTo(3);
        });
        assertThat(provider.otherCalls).isEmpty();
        assertThat(warnings()).isEmpty();
        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("AC-6: no favourites gives [] with no provider call and no clock read")
    void emptyList() {
        when(repository.findAll(CLIENT)).thenReturn(List.of());

        assertThat(service.forecastForAll(CLIENT, Units.METRIC, 5)).isEmpty();
        assertThat(provider.calls).isEmpty();
        assertThat(provider.otherCalls).isEmpty();
        assertThat(serviceClock.reads()).isZero();
    }

    @Test
    @DisplayName("AC-14/AC-17: expected failures become per-item failures; one WARN with counts only")
    void partialFailure() {
        List<Favourite> list = List.of(favourite("London", "LBL-SECRET"), favourite("Paris", null),
                favourite("Tokyo", null));
        when(repository.findAll(CLIENT)).thenReturn(list);
        provider.failAt(list.get(1).location(), () -> new WeatherProviderUnavailableException("down", null));
        provider.failAt(list.get(2).location(), () -> new LocationNotFoundException("Tokyo"));

        List<FavouriteForecast> items = service.forecastForAll(CLIENT, Units.METRIC, 5);

        assertThat(items.get(0).forecast()).isNotNull();
        assertThat(items.get(1).failure()).isEqualTo(FavouriteWeatherFailure.UPSTREAM_UNAVAILABLE);
        assertThat(items.get(1).forecast()).isNull();
        assertThat(items.get(2).failure()).isEqualTo(FavouriteWeatherFailure.LOCATION_NOT_FOUND);
        assertThat(warnings()).singleElement().satisfies(e -> {
            String message = e.getFormattedMessage();
            assertThat(message).isEqualTo("Favourites forecast partial failure failed=2 total=3 timedOut=0");
            assertThat(message).doesNotContain("London", "Paris", "Tokyo", "LBL-SECRET", CLIENT, "48.8566",
                    "Favourites weather");
        });
    }

    @Test
    @DisplayName("AC-14: one failed item gives failed=1 total=3 timedOut=0")
    void oneFailure() {
        List<Favourite> list = List.of(favourite("London", null), favourite("Paris", null), favourite("Tokyo", null));
        when(repository.findAll(CLIENT)).thenReturn(list);
        provider.failAt(list.get(1).location(), () -> new WeatherProviderUnavailableException("down", null));

        service.forecastForAll(CLIENT, Units.METRIC, 5);

        assertThat(warnings()).singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
                .isEqualTo("Favourites forecast partial failure failed=1 total=3 timedOut=0"));
    }

    @Test
    @DisplayName("AC-15: every item failing is still a full list of failures")
    void allFail() {
        List<Favourite> list = List.of(favourite("London", null), favourite("Paris", null), favourite("Tokyo", null));
        when(repository.findAll(CLIENT)).thenReturn(list);
        list.forEach(f -> provider.failAt(f.location(), () -> new WeatherProviderUnavailableException("down", null)));

        List<FavouriteForecast> items = service.forecastForAll(CLIENT, Units.METRIC, 5);

        assertThat(items).hasSize(3)
                .allSatisfy(i -> assertThat(i.failure()).isEqualTo(FavouriteWeatherFailure.UPSTREAM_UNAVAILABLE));
        assertThat(warnings()).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("failed=3 total=3 timedOut=0"));
    }

    @Test
    @DisplayName("AC-18: an unexpected exception propagates unchanged")
    void bugPropagates() {
        List<Favourite> list = List.of(favourite("London", null), favourite("Paris", null));
        when(repository.findAll(CLIENT)).thenReturn(list);
        IllegalStateException bug = new IllegalStateException("bug");
        provider.failAt(list.get(1).location(), () -> bug);

        assertThatThrownBy(() -> service.forecastForAll(CLIENT, Units.METRIC, 5)).isSameAs(bug);
    }

    @Test
    @DisplayName("AC-18/AC-26: a repository failure propagates, the provider is untouched, only findAll is called")
    void repositoryFailure() {
        DataAccessResourceFailureException down = new DataAccessResourceFailureException("x");
        when(repository.findAll(CLIENT)).thenThrow(down);

        assertThatThrownBy(() -> service.forecastForAll(CLIENT, Units.METRIC, 5)).isSameAs(down);
        assertThat(provider.calls).isEmpty();
        assertThat(provider.otherCalls).isEmpty();
        verify(repository).findAll(CLIENT);
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("AC-26: a successful request only reads the favourites")
    void readOnly() {
        when(repository.findAll(CLIENT)).thenReturn(List.of(favourite("London", null)));

        service.forecastForAll(CLIENT, Units.METRIC, 5);

        verify(repository).findAll(CLIENT);
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("AC-20: an item that misses the time budget is UPSTREAM_UNAVAILABLE and counted as timed out")
    void timedOutItem() {
        FavouriteForecastService quick = service(Clock.fixed(NOW, ZoneOffset.UTC), 2, Duration.ofSeconds(1));
        List<Favourite> list = List.of(favourite("London", null), favourite("Tokyo", null));
        when(repository.findAll(CLIENT)).thenReturn(list);
        provider.blockAt(list.get(1).location());

        List<FavouriteForecast> items = quick.forecastForAll(CLIENT, Units.METRIC, 5);

        assertThat(items.get(0).forecast()).isNotNull();
        assertThat(items.get(1).failure()).isEqualTo(FavouriteWeatherFailure.UPSTREAM_UNAVAILABLE);
        assertThat(warnings()).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("failed=1 total=2 timedOut=1"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    @DisplayName("AC-13: the start date is read once per request, even when the clock crosses UTC midnight")
    void oneStartDatePerRequest(int maxConcurrency) {
        CountingClock moving = new CountingClock(
                Instant.parse("2026-11-15T23:59:59.900Z"), Instant.parse("2026-11-16T00:00:00.100Z"));
        provider.readClockOnEveryCall(moving);
        FavouriteForecastService midnight = service(moving, maxConcurrency, Duration.ofSeconds(10));
        List<Favourite> list = List.of(favourite("London", null), favourite("Paris", null),
                favourite("Tokyo", null), favourite("Sydney", null), favourite("New York", null));
        when(repository.findAll(CLIENT)).thenReturn(list);

        List<FavouriteForecast> items = midnight.forecastForAll(CLIENT, Units.METRIC, 3);

        assertThat(provider.calls).hasSize(5)
                .allSatisfy(c -> assertThat(c.startDate()).isEqualTo(START));
        assertThat(items).allSatisfy(i -> assertThat(i.forecast().days().getFirst().date()).isEqualTo(START));
        assertThat(moving.reads()).isGreaterThan(1);
    }

    private record Call(Location location, LocalDate startDate, int days) {}

    /** A UTC clock that answers {@code first} on its first read and {@code later} afterwards. */
    private static final class CountingClock extends Clock {
        private final Instant first;
        private final Instant later;
        private final AtomicInteger reads = new AtomicInteger();

        CountingClock(Instant first, Instant later) {
            this.first = first;
            this.later = later;
        }

        int reads() {
            return reads.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return reads.getAndIncrement() == 0 ? first : later;
        }
    }

    /** Thread-safe provider fake: the stub's coordinate lookup, with per-location failures or blocking. */
    private static final class FakeProvider implements WeatherProvider {
        private final List<Call> calls = new CopyOnWriteArrayList<>();
        private final List<String> otherCalls = new CopyOnWriteArrayList<>();
        private final Map<Location, Supplier<RuntimeException>> failures = new ConcurrentHashMap<>();
        private final Map<Location, Boolean> blocked = new ConcurrentHashMap<>();
        private volatile Clock clockToRead;

        void failAt(Location location, Supplier<RuntimeException> failure) {
            failures.put(location, failure);
        }

        void blockAt(Location location) {
            blocked.put(location, true);
        }

        void readClockOnEveryCall(Clock clock) {
            this.clockToRead = clock;
        }

        @Override
        public LocationForecast forecastAtLocation(Location location, LocalDate startDate, int days) {
            Clock clock = clockToRead;
            if (clock != null) {
                clock.instant();
            }
            calls.add(new Call(location, startDate, days));
            Supplier<RuntimeException> failure = failures.get(location);
            if (failure != null) {
                throw failure.get();
            }
            if (blocked.containsKey(location)) {
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    throw new WeatherProviderUnavailableException("interrupted", null);
                }
            }
            return STUB.forecastAtLocation(location, startDate, days);
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
        public ObservedConditions currentAtLocation(Location location, Instant now) {
            otherCalls.add("currentAtLocation");
            return STUB.currentAtLocation(location, now);
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
