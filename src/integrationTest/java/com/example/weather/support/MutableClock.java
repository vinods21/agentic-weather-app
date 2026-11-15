package com.example.weather.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A UTC {@link Clock} that tests can move with {@link #set(Instant)}; thread-safe. */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public MutableClock(Instant initial) {
        this(new AtomicReference<>(initial), ZoneOffset.UTC);
    }

    private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public void set(Instant instant) {
        now.set(instant);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    /** A view in another zone that shares this clock's instant (moves with it). */
    @Override
    public Clock withZone(ZoneId newZone) {
        return newZone.equals(zone) ? this : new MutableClock(now, newZone);
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
