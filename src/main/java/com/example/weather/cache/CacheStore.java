package com.example.weather.cache;

import java.time.Duration;

/**
 * Minimal string key/value store behind {@link RedisWeatherCache} (ADR 0007 §1). Implementations
 * may throw {@link org.springframework.dao.DataAccessException} (or a Lettuce
 * {@code RedisException}); the cache treats those as fail-open failures.
 */
public interface CacheStore {

    /** Returns the value stored under {@code key}, or {@code null} if absent. */
    String get(String key);

    /** Stores {@code value} under {@code key}, expiring after {@code ttl} (one {@code SET ... PX}). */
    void set(String key, String value, Duration ttl);
}
