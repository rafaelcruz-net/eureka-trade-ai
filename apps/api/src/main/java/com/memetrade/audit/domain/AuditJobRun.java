package com.memetrade.audit.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Last known state of one daily job, as kept in {@code audit_job_run} (reliability-design.md, Q4).
 *
 * @param lastRunAt start of the last run
 * @param lastResult result of the last run
 * @param lastSuccessAt end of the last {@link AuditJobResult#COMPLETED} run; empty when none succeeded yet
 * @param lastCutoff cutoff used by the last run
 */
public record AuditJobRun(
        AuditJob job,
        Instant lastRunAt,
        AuditJobResult lastResult,
        Optional<Instant> lastSuccessAt,
        Optional<Instant> lastCutoff) {

    public AuditJobRun {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(lastRunAt, "lastRunAt");
        Objects.requireNonNull(lastResult, "lastResult");
        Objects.requireNonNull(lastSuccessAt, "lastSuccessAt");
        Objects.requireNonNull(lastCutoff, "lastCutoff");
    }
}
