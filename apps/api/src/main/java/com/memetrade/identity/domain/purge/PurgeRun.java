package com.memetrade.identity.domain.purge;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The last run of an Identity purge (entities.md PurgeRun, BR2.16): one row per purge in {@code identity_job_run},
 * written at the end of each run and read by the health of every instance.
 *
 * @param lastRunAt when the last run started
 * @param lastSuccessAt the end of the last SUCCEEDED run; kept when a later run fails
 * @param lastCutoff the cutoff the last run used
 * @param deleted rows of the main table the last run deleted
 */
public record PurgeRun(
        IdentityPurge purge,
        Instant lastRunAt,
        PurgeResult lastResult,
        Optional<Instant> lastSuccessAt,
        Instant lastCutoff,
        long deleted) {

    public PurgeRun {
        Objects.requireNonNull(purge, "purge");
        Objects.requireNonNull(lastRunAt, "lastRunAt");
        Objects.requireNonNull(lastResult, "lastResult");
        Objects.requireNonNull(lastSuccessAt, "lastSuccessAt");
        Objects.requireNonNull(lastCutoff, "lastCutoff");
        if (deleted < 0) {
            throw new IllegalArgumentException("deleted must not be negative");
        }
    }
}
