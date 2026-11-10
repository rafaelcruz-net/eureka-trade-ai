package com.memetrade.privacy.domain.purge;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Health and startup recovery of the Privacy purges (NFR7.1), the same policy as the Identity purges:
 *
 * <ul>
 *   <li><b>Healthy</b> while at most {@code degradedAfter} (48 hours) passed since the process started, or while the
 *       last success is at most that old; DEGRADED only when both are older;
 *   <li><b>Run at startup</b> when there is no row, no success, or the last success is older than
 *       {@code recoverAfter} (24 hours).
 * </ul>
 */
public final class PurgeHealthPolicy {

    private final Duration degradedAfter;
    private final Duration recoverAfter;

    public PurgeHealthPolicy(Duration degradedAfter, Duration recoverAfter) {
        this.degradedAfter = Objects.requireNonNull(degradedAfter, "degradedAfter");
        this.recoverAfter = Objects.requireNonNull(recoverAfter, "recoverAfter");
    }

    public boolean isHealthy(Optional<PurgeRun> run, Instant processStartedAt, Instant now) {
        if (!Duration.between(processStartedAt, now).minus(degradedAfter).isPositive()) {
            return true;
        }
        return sinceLastSuccess(run, now)
                .map(elapsed -> !elapsed.minus(degradedAfter).isPositive())
                .orElse(false);
    }

    public boolean shouldRunAtStartup(Optional<PurgeRun> run, Instant now) {
        return sinceLastSuccess(run, now)
                .map(elapsed -> elapsed.minus(recoverAfter).isPositive())
                .orElse(true);
    }

    /** Time since the last success; empty without a row or without a success. */
    public static Optional<Duration> sinceLastSuccess(Optional<PurgeRun> run, Instant now) {
        return run.flatMap(PurgeRun::lastSuccessAt).map(success -> Duration.between(success, now));
    }
}
