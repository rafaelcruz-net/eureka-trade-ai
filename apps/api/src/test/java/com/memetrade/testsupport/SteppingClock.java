package com.memetrade.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test clock that moves forward by a fixed step on every read. Used to cross a job's time limit deterministically,
 * without {@code Thread.sleep} and without changing the production limit (a reduced limit is set in the test).
 */
public final class SteppingClock extends Clock {

    private final AtomicReference<Instant> next;
    private final Duration step;

    public SteppingClock(Instant start, Duration step) {
        this.next = new AtomicReference<>(start);
        this.step = step;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("SteppingClock is always UTC");
    }

    @Override
    public Instant instant() {
        return next.getAndUpdate(current -> current.plus(step));
    }
}
