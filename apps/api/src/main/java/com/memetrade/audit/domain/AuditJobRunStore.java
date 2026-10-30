package com.memetrade.audit.domain;

import java.time.Instant;
import java.util.Optional;

/** Persistence port of the job state ({@code audit_job_run}), written by the jobs and read by recovery and health. */
public interface AuditJobRunStore {

    /** The state of {@code job}; empty on a new database, before its first run. */
    Optional<AuditJobRun> find(AuditJob job);

    /**
     * Records a finished run in its own transaction. {@code lastSuccessAt} becomes {@code endedAt} only when the
     * result is {@link AuditJobResult#COMPLETED}; otherwise the previous success is kept.
     */
    void record(AuditJob job, Instant startedAt, AuditJobResult result, Instant endedAt, Instant cutoff);
}
