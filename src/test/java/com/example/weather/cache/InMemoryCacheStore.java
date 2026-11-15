package com.example.weather.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Test double for {@link CacheStore}: a map with recorded TTLs and programmable failures. */
public class InMemoryCacheStore implements CacheStore {

    private final Map<String, String> values = new HashMap<>();
    private final Map<String, Duration> ttls = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private Supplier<RuntimeException> getFailure;
    private Supplier<RuntimeException> setFailure;

    @Override
    public String get(String key) {
        calls.add("get " + key);
        if (getFailure != null) {
            throw getFailure.get();
        }
        return values.get(key);
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        calls.add("set " + key);
        if (setFailure != null) {
            throw setFailure.get();
        }
        values.put(key, value);
        ttls.put(key, ttl);
    }

    public void failGets(Supplier<RuntimeException> failure) {
        this.getFailure = failure;
    }

    public void failSets(Supplier<RuntimeException> failure) {
        this.setFailure = failure;
    }

    /** Seeds a raw value (bypassing any failure), as a tampered or foreign entry would be. */
    public void seed(String key, String value) {
        values.put(key, value);
        ttls.put(key, Duration.ofMinutes(5));
    }

    public Map<String, String> values() {
        return values;
    }

    public Duration ttl(String key) {
        return ttls.get(key);
    }

    public List<String> calls() {
        return calls;
    }
}
