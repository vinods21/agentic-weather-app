package com.example.weather.support;

import com.example.weather.weather.Location;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * Real Open-Meteo adapter against {@link FakeOpenMeteo} with caching enabled and the default TTLs
 * set explicitly (requirements A-13), so an exported env var can't change the outcome. The
 * service clock is one shared {@link MutableClock}, reset to {@link #T0} before every test; the
 * run's Redis keys are deleted before (and, via the base class, after) every test.
 */
@TestPropertySource(properties = {
    "weather.cache.enabled=true",
    "weather.cache.geocoding-ttl=7d",
    "weather.cache.not-found-ttl=1h",
    "weather.cache.current-ttl=10m",
    "weather.cache.forecast-ttl=1h",
})
public abstract class CachingIntegrationTestBase extends RealProviderIntegrationTestBase {

    public static final Instant T0 = Instant.parse("2026-11-15T10:00:00Z");
    /** The location the fake's default geocoding returns for London. */
    public static final Location LONDON = new Location("London", "GB", 51.50853, -0.12574);

    protected static final MutableClock CLOCK = new MutableClock(T0);

    @TestBean
    Clock clock;

    static Clock clock() {
        return CLOCK;
    }

    @BeforeEach
    void resetClockAndCache() {
        CLOCK.set(T0);
        deleteRunRedisKeys();
    }

    protected Set<String> runKeys() {
        return RunRedisKeys.scan(redis, cacheKeys);
    }

    /** Remaining TTL in milliseconds ({@code -1} = persistent, {@code -2} = absent). */
    protected long pttl(String key) {
        Long ms = redis.getExpire(key, TimeUnit.MILLISECONDS);
        return ms == null ? -2 : ms;
    }
}
