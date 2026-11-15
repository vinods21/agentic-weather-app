package com.example.weather.support;

import com.example.weather.cache.CacheKeys;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/** SCAN of every key under the per-run prefix ({@link CacheKeys#pattern()}). */
public final class RunRedisKeys {

    private RunRedisKeys() {
    }

    public static Set<String> scan(StringRedisTemplate redis, CacheKeys keys) {
        Set<String> found = new TreeSet<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(keys.pattern()).count(500).build())) {
            cursor.forEachRemaining(found::add);
        }
        return found;
    }
}
