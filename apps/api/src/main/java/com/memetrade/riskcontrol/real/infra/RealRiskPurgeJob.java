package com.memetrade.riskcontrol.real.infra;

import com.memetrade.riskcontrol.infra.RiskRetentionDataSource;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.web.CorrelationId;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 261113-trading-real-bot U3 (U3-NFR3.7, scalability-design.md, logical-components.md "Desvio de Q1 e Q6"): the daily
 * purge of the REAL barrier, at 04:00 UTC in the api (after the PAPER purge of 03:30), once across instances (ShedLock
 * {@code real-risk-purge}, at most 45 minutes), through the retention pool of the module (one connection, the retention
 * login, member of {@code risk_retention}).
 *
 * <ol>
 *   <li>For each table, in batches of at most 1.000 rows in auto-commit (one transaction per batch): the evaluations, the
 *       CLOSED reservations (an OPEN one is never selected) and the photos whose instant is older than the database
 *       {@code now()} minus 13 months and 1 day. The trigger of the migration 0018 checks again the role and the 13
 *       months; the day of margin makes every selected row pass it, and a wrong selection fails the batch without
 *       deleting a recent row.
 *   <li>Batches repeat until one comes back short or 30 minutes passed; the rest waits for the next day.
 *   <li>Every round, succeeded or failed, writes one row of {@code real_risk_purge_run} (totals only) and deletes the rows
 *       of that log older than 90 days. A successful round moves the heartbeat of the job {@code purge}.
 * </ol>
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class RealRiskPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RealRiskPurgeJob.class);

    static final String LOCK_NAME = "real-risk-purge";
    static final String SUCCEEDED = "SUCCEEDED";
    static final String FAILED = "FAILED";
    static final int BATCH_SIZE = 1000;
    static final Duration MAX_RUN = Duration.ofMinutes(30);
    static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(45);

    /** The selection: one day more than the 13 months the trigger enforces. */
    static final String SELECTION = "13 months 1 day";

    /** One table of the purge: its key columns, the instant that ages it and an extra filter. */
    private record Target(String table, String keyColumns, String instant, String filter) {}

    private static final List<Target> TARGETS = List.of(
            new Target("real_risk_evaluation", "evaluation_id", "evaluated_at", ""),
            new Target("real_risk_reservation", "approval_id", "created_at", " and state <> 'OPEN'"),
            new Target("real_risk_day", "bot_wallet_id, day", "taken_at", ""));

    private final RiskRetentionDataSource retention;
    private final LockingTaskExecutor locks;
    private final RealRiskMetrics metrics;
    private final Clock clock;
    private final String selection;

    @Autowired
    public RealRiskPurgeJob(
            RiskRetentionDataSource retention, LockingTaskExecutor locks, RealRiskMetrics metrics, Clock clock) {
        this(retention, locks, metrics, clock, SELECTION);
    }

    /** @param selection the age of the selected rows, as a PostgreSQL interval (tests prove the trigger with less) */
    RealRiskPurgeJob(
            RiskRetentionDataSource retention,
            LockingTaskExecutor locks,
            RealRiskMetrics metrics,
            Clock clock,
            String selection) {
        this.retention = retention;
        this.locks = locks;
        this.metrics = metrics;
        this.clock = clock;
        this.selection = selection;
    }

    /**
     * One round.
     *
     * @param ran false when another instance held the lock (nothing done, nothing recorded)
     * @param result {@code SUCCEEDED} or {@code FAILED}; empty when the round did not run
     */
    public record Round(
            boolean ran,
            String result,
            long deleted,
            int batches,
            Optional<String> failureCause,
            Optional<UUID> runId) {}

    @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
    public void scheduled() {
        runOnce();
    }

    public Round runOnce() {
        return CorrelationId.withNew(() -> {
            LockConfiguration lock = new LockConfiguration(clock.instant(), LOCK_NAME, LOCK_AT_MOST_FOR, Duration.ZERO);
            try {
                LockingTaskExecutor.TaskResult<Round> result = locks.executeWithLock(this::round, lock);
                if (!result.wasExecuted()) {
                    log.info("REAL risk purge skipped: another instance holds the lock");
                    return new Round(false, "", 0, 0, Optional.empty(), Optional.empty());
                }
                return result.getResult();
            } catch (RuntimeException | Error unexpected) {
                throw unexpected;
            } catch (Throwable checked) {
                throw new IllegalStateException("The REAL risk purge failed with a checked exception", checked);
            }
        });
    }

    private Round round() {
        Instant started = clock.instant();
        Instant deadline = started.plus(MAX_RUN);
        long startedNanos = System.nanoTime();
        Instant cutoff = started;
        long deleted = 0;
        int batches = 0;
        try {
            cutoff = retention
                    .jdbc()
                    .sql("select now() - cast(:selection as interval)")
                    .param("selection", selection)
                    .query(Timestamp.class)
                    .single()
                    .toInstant();
            for (Target target : TARGETS) {
                while (clock.instant().isBefore(deadline)) {
                    int batch = deleteBatch(target);
                    batches++;
                    deleted += batch;
                    if (batch < BATCH_SIZE) {
                        break;
                    }
                }
            }
            int cleaned = retention
                    .jdbc()
                    .sql("delete from real_risk_purge_run where started_at < now() - interval '90 days'")
                    .update();
            UUID runId = record(started, cutoff, deleted, batches, SUCCEEDED, null);
            metrics.jobSucceeded(
                    RealRiskPurgeJob.JOB, Duration.ofNanos(System.nanoTime() - startedNanos), clock.instant());
            log.info(
                    "REAL risk purge finished: cutoff={}, deleted={}, batches={}, runLogCleaned={}",
                    cutoff,
                    deleted,
                    batches,
                    cleaned);
            return new Round(true, SUCCEEDED, deleted, batches, Optional.empty(), Optional.of(runId));
        } catch (RuntimeException failure) {
            String cause = failure.getClass().getSimpleName();
            log.error(
                    "REAL risk purge failed: cause={}, deletedBeforeFailure={}, batches={}",
                    cause,
                    deleted,
                    batches,
                    failure);
            Optional<UUID> runId = recordFailure(started, cutoff, deleted, batches, cause);
            return new Round(true, FAILED, deleted, batches, Optional.of(cause), runId);
        }
    }

    static final String JOB = "purge";

    private int deleteBatch(Target target) {
        String sql = "delete from " + target.table() + " where (" + target.keyColumns() + ") in (select "
                + target.keyColumns() + " from " + target.table() + " where " + target.instant()
                + " < now() - cast(:selection as interval)"
                + target.filter() + " order by " + target.instant() + " limit :limit)";
        return retention
                .jdbc()
                .sql(sql)
                .param("selection", selection)
                .param("limit", BATCH_SIZE)
                .update();
    }

    private UUID record(Instant started, Instant cutoff, long deleted, int batches, String result, String cause) {
        UUID runId = UUID.randomUUID();
        retention
                .jdbc()
                .sql("""
                        insert into real_risk_purge_run (run_id, started_at, finished_at, cutoff, deleted_count,
                          batches, result, failure_cause)
                        values (:id, :started, :finished, :cutoff, :deleted, :batches, :result, :cause)""")
                .param("id", runId)
                .param("started", Timestamp.from(started))
                .param("finished", Timestamp.from(clock.instant()))
                .param("cutoff", Timestamp.from(cutoff))
                .param("deleted", deleted)
                .param("batches", batches)
                .param("result", result)
                .param("cause", cause)
                .update();
        return runId;
    }

    private Optional<UUID> recordFailure(Instant started, Instant cutoff, long deleted, int batches, String cause) {
        try {
            return Optional.of(record(started, cutoff, deleted, batches, FAILED, cause));
        } catch (RuntimeException notRecorded) {
            log.error(
                    "REAL risk purge round could not be recorded: cause={}",
                    notRecorded.getClass().getSimpleName(),
                    notRecorded);
            return Optional.empty();
        }
    }
}
