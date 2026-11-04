package com.memetrade.shared.health;

import java.time.Instant;
import java.util.Optional;

/** Last round instants of a scheduled job, read by a health indicator (U7, NFR9.1). Thread safe. */
public final class JobRounds {

    private volatile Instant lastRunAt;
    private volatile Instant lastSuccessAt;
    private volatile boolean lastFailed;

    public void succeeded(Instant at) {
        lastRunAt = at;
        lastSuccessAt = at;
        lastFailed = false;
    }

    public void failed(Instant at) {
        lastRunAt = at;
        lastFailed = true;
    }

    public Optional<Instant> lastRunAt() {
        return Optional.ofNullable(lastRunAt);
    }

    public Optional<Instant> lastSuccessAt() {
        return Optional.ofNullable(lastSuccessAt);
    }

    public boolean lastFailed() {
        return lastFailed;
    }
}
