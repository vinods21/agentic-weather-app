package com.example.weather.cache;

import com.example.weather.weather.CurrentConditions;
import com.example.weather.weather.DailyForecast;
import com.example.weather.weather.Location;
import com.fasterxml.jackson.core.JacksonException;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;

/**
 * Redis-backed {@link WeatherCache} (ADR 0007). Fails open with a narrow catch set:
 * <ul>
 *   <li>store errors ({@link DataAccessException}, Lettuce {@link RedisException}) are
 *       {@code reason=unavailable} and trip the session's short circuit, except a Redis
 *       {@code WRONGTYPE} reply, which is {@code reason=unreadable};</li>
 *   <li>decode errors ({@link JacksonException}, {@link UnreadableCacheEntryException}) are
 *       {@code reason=unreadable}; the following put rewrites the key.</li>
 * </ul>
 * Any other exception is a bug and propagates. Each failure is one WARN line without a
 * throwable, message, city, key or value; hits and misses are DEBUG only.
 */
public class RedisWeatherCache implements WeatherCache {

    private static final Logger log = LoggerFactory.getLogger(RedisWeatherCache.class);
    private static final int MAX_CAUSE_DEPTH = 16;

    private final CacheStore store;
    private final CacheKeys keys;
    private final WeatherCacheProperties properties;
    private final CacheCodec codec;

    public RedisWeatherCache(CacheStore store, CacheKeys keys, WeatherCacheProperties properties) {
        this(store, keys, properties, new CacheCodec());
    }

    RedisWeatherCache(CacheStore store, CacheKeys keys, WeatherCacheProperties properties, CacheCodec codec) {
        this.store = store;
        this.keys = keys;
        this.properties = properties;
        this.codec = codec;
    }

    @Override
    public Session session() {
        return new RedisSession();
    }

    private enum Kind {
        GEOCODING("geocoding"),
        CURRENT("current"),
        FORECAST("forecast");

        final String logName;

        Kind(String logName) {
            this.logName = logName;
        }
    }

    @FunctionalInterface
    private interface Decoder<T> {
        T decode(String raw) throws JacksonException;
    }

    private final class RedisSession implements Session {

        private boolean bypass;

        @Override
        public Optional<GeocodingEntry> geocoding(String city) {
            return read(Kind.GEOCODING, () -> keys.geocoding(city), codec::decodeGeocoding);
        }

        @Override
        public void putLocation(String city, Location location) {
            write(Kind.GEOCODING, () -> keys.geocoding(city), () -> codec.encodeLocation(location),
                    properties.geocodingTtl());
        }

        @Override
        public void putNotFound(String city) {
            write(Kind.GEOCODING, () -> keys.geocoding(city), codec::encodeNotFound, properties.notFoundTtl());
        }

        @Override
        public Optional<CurrentEntry> current(Location location) {
            return read(Kind.CURRENT, () -> keys.current(location), raw -> codec.decodeCurrent(raw, location));
        }

        @Override
        public void putCurrent(Location location, CurrentConditions conditions, Instant fetchedAt) {
            write(Kind.CURRENT, () -> keys.current(location), () -> codec.encodeCurrent(conditions, fetchedAt),
                    properties.currentTtl());
        }

        @Override
        public Optional<List<DailyForecast>> forecast(Location location, LocalDate startDate, int days) {
            return read(Kind.FORECAST, () -> keys.forecast(location, startDate, days),
                    raw -> codec.decodeForecast(raw, startDate, days));
        }

        @Override
        public void putForecast(Location location, LocalDate startDate, int days, List<DailyForecast> forecastDays) {
            write(Kind.FORECAST, () -> keys.forecast(location, startDate, days),
                    () -> codec.encodeForecast(forecastDays), properties.forecastTtl());
        }

        private <T> Optional<T> read(Kind kind, Supplier<String> key, Decoder<T> decoder) {
            if (bypass) {
                log.debug("Cache operation skipped op=get cache={}", kind.logName);
                return Optional.empty();
            }
            String k = key.get();
            String raw;
            try {
                raw = store.get(k);
            } catch (DataAccessException | RedisException e) {
                if (isWrongType(e)) {
                    warn("get", kind, "unreadable", e);
                } else {
                    bypass = true;
                    warn("get", kind, "unavailable", e);
                }
                return Optional.empty();
            }
            if (raw == null) {
                log.debug("Cache lookup cache={} outcome=miss", kind.logName);
                return Optional.empty();
            }
            try {
                T value = decoder.decode(raw);
                log.debug("Cache lookup cache={} outcome=hit", kind.logName);
                return Optional.of(value);
            } catch (JacksonException | UnreadableCacheEntryException e) {
                warn("get", kind, "unreadable", e);
                return Optional.empty();
            }
        }

        private void write(Kind kind, Supplier<String> key, Supplier<String> value, Duration ttl) {
            if (bypass) {
                log.debug("Cache operation skipped op=put cache={}", kind.logName);
                return;
            }
            String k = key.get();
            String v = value.get();
            try {
                store.set(k, v, ttl);
            } catch (DataAccessException | RedisException e) {
                bypass = true;
                warn("put", kind, "unavailable", e);
            }
        }
    }

    private static boolean isWrongType(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (t instanceof RedisCommandExecutionException
                    && t.getMessage() != null
                    && t.getMessage().startsWith("WRONGTYPE")) {
                return true;
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return false;
    }

    private static void warn(String op, Kind kind, String reason, Throwable e) {
        log.warn("Cache operation failed op={} cache={} reason={} exception={} cause={}",
                op, kind.logName, reason, e.getClass().getName(),
                NestedExceptionUtils.getMostSpecificCause(e).getClass().getName());
    }
}
