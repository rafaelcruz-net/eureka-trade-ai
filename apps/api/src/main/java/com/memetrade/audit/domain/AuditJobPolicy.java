package com.memetrade.audit.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Startup recovery and health rules of the daily jobs (reliability-design.md, NFR4.5, NFR9.1).
 *
 * @param recoverAfter a job with no success for longer than this runs when the API becomes ready (24 h)
 * @param degradedAfter a job with no success for longer than this makes its health DEGRADED (26 h)
 */
public record AuditJobPolicy(Duration recoverAfter, Duration degradedAfter) {

    public AuditJobPolicy {
        Objects.requireNonNull(recoverAfter, "recoverAfter");
        Objects.requireNonNull(degradedAfter, "degradedAfter");
    }

    /** True without a row, without any success, or when the last success is older than {@link #recoverAfter}. */
    public boolean shouldRunAtStartup(Optional<AuditJobRun> run, Instant now) {
        return sinceLastSuccess(run, now)
                .map(elapsed -> elapsed.compareTo(recoverAfter) > 0)
                .orElse(true);
    }

    /**
     * Healthy only when the last run COMPLETED and its success is at most {@link #degradedAfter} old. No row,
     * {@code FAILED}, {@code TIME_LIMIT} or an older success are degraded.
     */
    public boolean isHealthy(Optional<AuditJobRun> run, Instant now) {
        if (run.isEmpty() || run.get().lastResult() != AuditJobResult.COMPLETED) {
            return false;
        }
        return sinceLastSuccess(run, now)
                .map(elapsed -> elapsed.compareTo(degradedAfter) <= 0)
                .orElse(false);
    }

    /** Time since the last success; empty when the job never succeeded. */
    public static Optional<Duration> sinceLastSuccess(Optional<AuditJobRun> run, Instant now) {
        return run.flatMap(AuditJobRun::lastSuccessAt).map(success -> Duration.between(success, now));
    }
}
