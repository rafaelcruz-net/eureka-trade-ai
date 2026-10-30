package com.memetrade.audit.infra;

import com.memetrade.audit.domain.AuditJob;
import com.memetrade.audit.domain.AuditJobResult;
import com.memetrade.audit.domain.AuditJobRunStore;
import com.memetrade.audit.domain.BatchedDeletion;
import com.memetrade.audit.domain.BatchedDeletion.Outcome;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * Common run of the two daily jobs (F4, F5, reliability-design.md): cutoff, batched deletion with the time limit,
 * optional follow-up (the purge publishes its event), the {@code audit_job_run} row in its own transaction, metrics
 * and the start and end logs. Called only through {@link AuditJobRunner}, which holds the ShedLock lock and gives the
 * run its own {@code correlationId}.
 */
public abstract class AuditDailyJob {

    private static final Logger log = LoggerFactory.getLogger(AuditDailyJob.class);

    private final AuditJob job;
    private final AuditJobRunStore runs;
    private final AuditJobMetrics metrics;
    private final AuditProperties.Jobs settings;
    private final Clock clock;

    protected AuditDailyJob(
            AuditJob job, AuditJobRunStore runs, AuditJobMetrics metrics, AuditProperties.Jobs settings, Clock clock) {
        this.job = Objects.requireNonNull(job, "job");
        this.runs = runs;
        this.metrics = metrics;
        this.settings = settings;
        this.clock = clock;
    }

    /** Result of one run, returned to the runner and to tests. */
    public record RunResult(AuditJob job, AuditJobResult result, Instant cutoff, long deleted) {}

    public AuditJob job() {
        return job;
    }

    public final RunResult run() {
        Instant startedAt = clock.instant();
        Instant cutoff = cutoff(startedAt);
        log.info("Audit job started: job={}, cutoff={}", job, cutoff);
        Outcome outcome = new BatchedDeletion(settings.batchSize(), settings.timeLimit(), clock)
                .run(limit -> deleteBatch(cutoff, limit));
        AuditJobResult result = afterDeletion(cutoff, outcome);
        Instant endedAt = clock.instant();
        Duration took = Duration.between(startedAt, endedAt);
        recordRun(startedAt, result, endedAt, cutoff);
        metrics.finished(job, result, outcome.deleted(), took, endedAt);
        logEnd(result, cutoff, outcome, took);
        return new RunResult(job, result, cutoff, outcome.deleted());
    }

    /** Rows strictly before the returned instant may be deleted. */
    protected abstract Instant cutoff(Instant now);

    /** Deletes at most {@code limit} rows older than {@code cutoff} in one transaction; returns the count. */
    protected abstract int deleteBatch(Instant cutoff, int limit);

    /** Work after the deletion; may turn the result into FAILED. The default keeps the deletion result. */
    protected AuditJobResult afterDeletion(Instant cutoff, Outcome outcome) {
        return outcome.result();
    }

    /** Short technical cause for logs: exception class and SQL state, never a message with values. */
    static String technicalCause(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof SQLException sql) {
                return failure.getClass().getSimpleName() + " (SQLState " + sql.getSQLState() + ")";
            }
            cause = cause.getCause();
        }
        return failure.getClass().getSimpleName();
    }

    private void recordRun(Instant startedAt, AuditJobResult result, Instant endedAt, Instant cutoff) {
        try {
            runs.record(job, startedAt, result, endedAt, cutoff);
        } catch (DataAccessException unavailable) {
            // Without the row, the missing success makes the health DEGRADED after 26 h (reliability-design.md).
            log.error(
                    "Audit job state could not be recorded: job={}, result={}, cause={}",
                    job,
                    result,
                    technicalCause(unavailable));
        }
    }

    private void logEnd(AuditJobResult result, Instant cutoff, Outcome outcome, Duration took) {
        String cause = outcome.failure().map(AuditDailyJob::technicalCause).orElse("none");
        switch (result) {
            case COMPLETED ->
                log.info(
                        "Audit job finished: job={}, cutoff={}, deleted={}, durationMs={}, result={}",
                        job,
                        cutoff,
                        outcome.deleted(),
                        took.toMillis(),
                        result);
            case TIME_LIMIT ->
                log.warn(
                        "Audit job stopped at the time limit: job={}, cutoff={}, deleted={}, durationMs={}, result={}",
                        job,
                        cutoff,
                        outcome.deleted(),
                        took.toMillis(),
                        result);
            case FAILED ->
                log.error(
                        "Audit job failed: job={}, cutoff={}, deleted={}, durationMs={}, result={}, cause={}",
                        job,
                        cutoff,
                        outcome.deleted(),
                        took.toMillis(),
                        result,
                        cause);
        }
    }
}
