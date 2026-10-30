package com.memetrade.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** Fixed, manually advanced UTC clock for deadline tests; no test sleeps to wait for a domain deadline. */
public final class MutableClock extends Clock {

    public static final Instant DEFAULT_START = Instant.parse("2026-10-28T12:00:00Z");

    private final AtomicReference<Instant> now;

    public MutableClock() {
        this(DEFAULT_START);
    }

    public MutableClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    public void advance(Duration duration) {
        now.updateAndGet(current -> current.plus(duration));
    }

    public void set(Instant instant) {
        now.set(instant);
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
        return now.get();
    }
}
