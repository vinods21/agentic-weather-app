package com.example.weather.cache;

import static com.example.weather.cache.CacheCodecTest.CURRENT;
import static com.example.weather.cache.CacheCodecTest.DAYS;
import static com.example.weather.cache.CacheCodecTest.FETCHED_AT;
import static com.example.weather.cache.CacheCodecTest.LONDON;
import static com.example.weather.cache.CacheCodecTest.START;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.weather.weather.Location;
import io.lettuce.core.RedisCommandExecutionException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.RedisConnectionFailureException;

/** Unit test over {@link InMemoryCacheStore}: no Spring context, no Docker. */
@ExtendWith(OutputCaptureExtension.class)
class RedisWeatherCacheTest {

    private static final WeatherCacheProperties PROPS = WeatherCacheProperties.defaults();

    private final CacheKeys keys = new CacheKeys("weather");
    private final InMemoryCacheStore store = new InMemoryCacheStore();
    private final RedisWeatherCache cache = new RedisWeatherCache(store, keys, PROPS);

    @Test
    @DisplayName("AC-11/AC-12: each kind round-trips and is written with its own TTL")
    void roundTripsWithTtlPerKind() {
        WeatherCache.Session s = cache.session();
        s.putLocation("London", LONDON);
        s.putNotFound("Atlantis");
        s.putCurrent(LONDON, CURRENT, FETCHED_AT);
        s.putForecast(LONDON, START, 3, DAYS);

        assertThat(store.ttl(keys.geocoding("London"))).isEqualTo(Duration.ofDays(7));
        assertThat(store.ttl(keys.geocoding("Atlantis"))).isEqualTo(Duration.ofHours(1));
        assertThat(store.ttl(keys.current(LONDON))).isEqualTo(Duration.ofMinutes(10));
        assertThat(store.ttl(keys.forecast(LONDON, START, 3))).isEqualTo(Duration.ofHours(1));

        WeatherCache.Session read = cache.session();
        assertThat(read.geocoding("london")).contains(new GeocodingEntry.Found(LONDON));
        assertThat(read.geocoding("ATLANTIS")).contains(new GeocodingEntry.NotFound());
        assertThat(read.current(LONDON)).contains(new CurrentEntry(CURRENT, FETCHED_AT));
        assertThat(read.forecast(LONDON, START, 3)).contains(DAYS);
        assertThat(read.forecast(LONDON, START, 2)).isEmpty();
        assertThat(read.geocoding("Paris")).isEmpty();
    }

    @Test
    @DisplayName("AC-12: configured TTLs are used")
    void configuredTtls() {
        WeatherCacheProperties props = new WeatherCacheProperties(true, Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofSeconds(3), Duration.ofSeconds(4));
        WeatherCache.Session s = new RedisWeatherCache(store, keys, props).session();
        s.putLocation("London", LONDON);
        s.putNotFound("Atlantis");
        s.putCurrent(LONDON, CURRENT, FETCHED_AT);
        s.putForecast(LONDON, START, 3, DAYS);
        assertThat(store.ttl(keys.geocoding("London"))).isEqualTo(Duration.ofSeconds(1));
        assertThat(store.ttl(keys.geocoding("Atlantis"))).isEqualTo(Duration.ofSeconds(2));
        assertThat(store.ttl(keys.current(LONDON))).isEqualTo(Duration.ofSeconds(3));
        assertThat(store.ttl(keys.forecast(LONDON, START, 3))).isEqualTo(Duration.ofSeconds(4));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not-json", "{}", "{\"v\":0,\"found\":false}", "{\"v\":1,\"found\":\"yes\"}",
    })
    @DisplayName("AC-23: an unreadable geocoding entry is a miss with a reason=unreadable WARN, and can be rewritten")
    void unreadableGeocoding(String raw, CapturedOutput output) {
        String key = keys.geocoding("London");
        store.seed(key, raw);

        WeatherCache.Session s = cache.session();
        assertThat(s.geocoding("London")).isEmpty();
        s.putLocation("London", LONDON);

        assertThat(output.getAll()).contains(
                "Cache operation failed op=get cache=geocoding reason=unreadable exception=");
        assertThat(cache.session().geocoding("London")).contains(new GeocodingEntry.Found(LONDON));
    }

    @Test
    @DisplayName("AC-23: unreadable current and short forecast entries are misses")
    void unreadableCurrentAndForecast(CapturedOutput output) {
        store.seed(keys.current(LONDON), "{\"v\":1}");
        CacheCodec codec = new CacheCodec();
        store.seed(keys.forecast(LONDON, START, 3), codec.encodeForecast(DAYS.subList(0, 2)));

        WeatherCache.Session s = cache.session();
        assertThat(s.current(LONDON)).isEmpty();
        assertThat(s.forecast(LONDON, START, 3)).isEmpty();
        assertThat(output.getAll())
                .contains("op=get cache=current reason=unreadable")
                .contains("op=get cache=forecast reason=unreadable");
    }

    @Test
    @DisplayName("AC-23: WRONGTYPE is unreadable and does not trip the short circuit")
    void wrongTypeIsUnreadable(CapturedOutput output) {
        store.failGets(() -> new InvalidDataAccessApiUsageException("WRONGTYPE Operation against a key",
                new RedisCommandExecutionException("WRONGTYPE Operation against a key holding the wrong kind of value")));

        WeatherCache.Session s = cache.session();
        assertThat(s.geocoding("London")).isEmpty();
        s.putLocation("London", LONDON);

        assertThat(output.getAll()).contains("Cache operation failed op=get cache=geocoding reason=unreadable "
                + "exception=org.springframework.dao.InvalidDataAccessApiUsageException "
                + "cause=io.lettuce.core.RedisCommandExecutionException");
        assertThat(store.calls()).containsExactly("get " + keys.geocoding("London"), "set " + keys.geocoding("London"));
    }

    @Test
    @DisplayName("AC-19/AC-22: a failed read is a miss, WARNs once, and the session skips the rest")
    void failedReadShortCircuits(CapturedOutput output) {
        store.failGets(() -> new RedisConnectionFailureException("simulated London weather:geocoding"));

        WeatherCache.Session s = cache.session();
        assertThat(s.geocoding("London")).isEmpty();
        s.putLocation("London", LONDON);
        assertThat(s.current(LONDON)).isEmpty();
        s.putCurrent(LONDON, CURRENT, FETCHED_AT);

        assertThat(store.calls()).hasSize(1);
        assertThat(warnLines(output)).containsExactly(
                "Cache operation failed op=get cache=geocoding reason=unavailable "
                        + "exception=org.springframework.data.redis.RedisConnectionFailureException "
                        + "cause=org.springframework.data.redis.RedisConnectionFailureException");

        // A new session tries again.
        assertThat(cache.session().geocoding("London")).isEmpty();
        assertThat(store.calls()).hasSize(2);
    }

    @Test
    @DisplayName("AC-22: a failed write does not throw, WARNs once, and the session skips the rest")
    void failedWriteIsSkipped(CapturedOutput output) {
        store.failSets(() -> new RedisConnectionFailureException("simulated"));

        WeatherCache.Session s = cache.session();
        assertThat(s.geocoding("London")).isEmpty();
        s.putLocation("London", LONDON);
        assertThat(s.current(LONDON)).isEmpty();
        s.putCurrent(LONDON, CURRENT, FETCHED_AT);

        assertThat(store.calls()).hasSize(2);
        assertThat(warnLines(output)).singleElement().asString()
                .startsWith("Cache operation failed op=put cache=geocoding reason=unavailable");
    }

    @Test
    @DisplayName("AC-20: WARN lines contain no city, key or value, and no stack trace")
    void warnLinesAreSanitised(CapturedOutput output) {
        Location secret = new Location("Secretville", "GB", 12.345, 67.891);
        store.seed(keys.current(secret), "{\"v\":1,\"temperature\":\"hot-value\"}");
        WeatherCache.Session s = cache.session();
        assertThat(s.current(secret)).isEmpty();
        store.failGets(() -> new RedisConnectionFailureException("Secretville weather:geocoding:abc"));
        assertThat(s.geocoding("Secretville")).isEmpty();

        String all = output.getAll();
        assertThat(warnLines(output)).hasSize(2);
        assertThat(all).doesNotContain("Secretville").doesNotContain("weather:").doesNotContain("hot-value")
                .doesNotContain("12.345").doesNotContain("\tat ");
    }

    @Test
    @DisplayName("AC-4: the disabled cache never touches a store")
    void disabledCache() {
        WeatherCache.Session s = WeatherCache.disabled().session();
        s.putLocation("London", LONDON);
        s.putNotFound("Atlantis");
        s.putCurrent(LONDON, CURRENT, FETCHED_AT);
        s.putForecast(LONDON, START, 3, DAYS);
        assertThat(s.geocoding("London")).isEmpty();
        assertThat(s.current(LONDON)).isEmpty();
        assertThat(s.forecast(LONDON, START, 3)).isEmpty();
    }

    private static List<String> warnLines(CapturedOutput output) {
        return output.getAll().lines()
                .filter(l -> l.contains("Cache operation failed"))
                .map(l -> l.substring(l.indexOf("Cache operation failed")))
                .toList();
    }
}
