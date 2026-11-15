package com.example.weather.cache;

import static com.example.weather.cache.CacheCodecTest.CURRENT;
import static com.example.weather.cache.CacheCodecTest.FETCHED_AT;
import static com.example.weather.cache.CacheCodecTest.LONDON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.RedisConnectionFailureException;

/** Edge cases of the fail-open catch set and the per-session short circuit (tester). No Spring, no Docker. */
@ExtendWith(OutputCaptureExtension.class)
class RedisWeatherCacheEdgeCaseTest {

    private final CacheKeys keys = new CacheKeys("weather");
    private final InMemoryCacheStore store = new InMemoryCacheStore();
    private final RedisWeatherCache cache = new RedisWeatherCache(store, keys, WeatherCacheProperties.defaults());

    @Test
    @DisplayName("AC-19/AC-21: a bare Lettuce RedisCommandTimeoutException on get is unavailable: miss, WARN, rest of the session skipped")
    void lettuceTimeoutOnGetTripsShortCircuit(CapturedOutput output) {
        store.failGets(() -> new RedisCommandTimeoutException("Command timed out after 250 ms"));
        WeatherCache.Session s = cache.session();

        assertThat(s.geocoding("London")).isEmpty();
        s.putLocation("London", LONDON);
        assertThat(s.current(LONDON)).isEmpty();

        assertThat(store.calls()).hasSize(1);
        assertThat(output.getAll()).contains("Cache operation failed op=get cache=geocoding reason=unavailable "
                + "exception=io.lettuce.core.RedisCommandTimeoutException")
                .doesNotContain("250 ms");
    }

    @Test
    @DisplayName("AC-22 (A-9): an exception outside the catch set (a bug) propagates instead of being swallowed")
    void programmingErrorPropagates() {
        store.failGets(() -> new IllegalStateException("bug"));

        assertThatThrownBy(() -> cache.session().geocoding("London")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("AC-23: an unreadable entry does not trip the short circuit: the next get and the rewrite still reach the store")
    void unreadableDoesNotBypass() {
        store.seed(keys.geocoding("London"), "not-json");
        WeatherCache.Session s = cache.session();

        assertThat(s.geocoding("London")).isEmpty();
        s.putLocation("London", LONDON);
        assertThat(s.current(LONDON)).isEmpty();
        s.putCurrent(LONDON, CURRENT, FETCHED_AT);

        assertThat(store.calls()).hasSize(4);
        assertThat(cache.session().geocoding("london")).contains(new GeocodingEntry.Found(LONDON));
    }

    @Test
    @DisplayName("AC-19: the short circuit is per session: a new session (the next request) tries Redis again")
    void bypassIsPerSession() {
        store.failGets(() -> new RedisConnectionFailureException("down"));
        assertThat(cache.session().geocoding("London")).isEmpty();
        store.failGets(null);
        store.seed(keys.geocoding("London"), "{\"v\":1,\"found\":false}");

        assertThat(cache.session().geocoding("London")).contains(new GeocodingEntry.NotFound());
        assertThat(store.calls()).hasSize(2);
    }

    @Test
    @DisplayName("AC-23: an unwrapped Lettuce WRONGTYPE error is unreadable too (no bypass)")
    void bareWrongTypeIsUnreadable(CapturedOutput output) {
        store.failGets(() -> new RedisCommandExecutionException(
                "WRONGTYPE Operation against a key holding the wrong kind of value"));
        WeatherCache.Session s = cache.session();

        assertThat(s.geocoding("London")).isEmpty();
        assertThat(s.current(LONDON)).isEmpty();

        assertThat(store.calls()).hasSize(2);
        assertThat(output.getAll()).contains("reason=unreadable").doesNotContain("reason=unavailable")
                .doesNotContain("wrong kind of value");
    }

    @Test
    @DisplayName("AC-22: a non-WRONGTYPE Redis error reply (e.g. NOAUTH) is unavailable and trips the short circuit")
    void otherErrorReplyIsUnavailable(CapturedOutput output) {
        store.failGets(() -> new InvalidDataAccessApiUsageException("NOAUTH",
                new RedisCommandExecutionException("NOAUTH Authentication required.")));
        WeatherCache.Session s = cache.session();

        assertThat(s.geocoding("London")).isEmpty();
        assertThat(s.current(LONDON)).isEmpty();

        assertThat(store.calls()).hasSize(1);
        assertThat(output.getAll()).contains("reason=unavailable")
                .contains("cause=io.lettuce.core.RedisCommandExecutionException")
                .doesNotContain("Authentication required");
    }

    @Test
    @DisplayName("AC-20: a WARN line never carries the key, even when the exception message contains it")
    void exceptionMessageWithKeyIsNotLogged(CapturedOutput output) {
        String key = keys.geocoding("London");
        store.failSets(() -> new RedisConnectionFailureException("failed SET " + key));

        cache.session().putLocation("London", LONDON);

        assertThat(output.getAll()).contains("Cache operation failed op=put cache=geocoding reason=unavailable")
                .doesNotContain(key).doesNotContain("London");
    }
}
