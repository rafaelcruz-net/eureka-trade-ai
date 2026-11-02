package com.memetrade.testsupport;

import com.memetrade.marketdata.infra.Sleeper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** {@link Sleeper} that never waits and records every requested wait, for retry tests (unit-test-instructions.md). */
public final class NoWaitSleeper implements Sleeper {

    private final List<Duration> waits = new CopyOnWriteArrayList<>();

    @Override
    public void sleep(Duration duration) {
        waits.add(duration);
    }

    public List<Duration> waits() {
        return List.copyOf(waits);
    }
}
