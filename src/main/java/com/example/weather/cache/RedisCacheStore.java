package com.example.weather.cache;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** {@link CacheStore} on a {@link StringRedisTemplate}: {@code GET} and {@code SET ... PX}. No logic, no logging. */
public class RedisCacheStore implements CacheStore {

    private final StringRedisTemplate redis;

    public RedisCacheStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public String get(String key) {
        return redis.opsForValue().get(key);
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }
}
