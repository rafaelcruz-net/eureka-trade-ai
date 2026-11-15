package com.memetrade.realexecution.infra;

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
 * 261113-trading-real-bot U4 (U4-NFR3.9, scalability-design.md, infrastructure-specification.md): the daily purge of the
 * REAL execution, at 04:30 UTC in the api (after the purge of the REAL barrier of 04:00), once across instances
 * (ShedLock {@code real-execution-purge}, at most 45 minutes), through the retention pool of U6 (one connection, the
 * retention login, member of {@code risk_retention}); the same shape as the purge of the U3.
 *
 * <ol>
 *   <li>In batches of at most 1.000 rows in auto-commit, in this order because of the foreign key: the sends of FINAL
 *       orders, the FINAL orders without any send left, the liquidations in {@code DONE} or {@code PARTIAL}, all older
 *       than 5 years and 1 day; then the decisions in {@code PROCESSED} or {@code DISCARDED} closed more than 13 months
 *       and 1 day ago. An order in progress, an active liquidation and a {@code PENDING} decision are never selected.
 *       The triggers of the migration 0019 check again the role and the age; the day of margin makes every selected row
 *       pass them, and a wrong selection fails the batch without deleting a recent row.
 *   <li>Batches repeat until one comes back short or 30 minutes passed; the rest waits for the next day.
 *   <li>Every round, succeeded or failed, writes one row of {@code real_execution_purge_run} (totals only) and deletes
 *       the rows of that log older than 90 days. A successful round counts the job {@code purge}.
 * </ol>
 *
 * <p>The retention pool belongs to the risk control (U6); this class is the named exception of the architecture rules
 * that lets one purge per Unit receive it (RealExecutionArchitectureTest, RiskControlArchitectureTest).
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class RealExecutionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RealExecutionPurgeJob.class);

    static final String LOCK_NAME = "real-execution-purge";
    static final String SUCCEEDED = "SUCCEEDED";
    static final String FAILED = "FAILED";
    static final int BATCH_SIZE = 1000;
    static final Duration MAX_RUN = Duration.ofMinutes(30);
    static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(45);

    /** The selections: one day more than the 5 years and the 13 months the triggers enforce. */
    static final String RECORDS = "5 years 1 day";

    static final String DECISIONS = "13 months 1 day";

    private static final String FINAL_ORDERS =
            "select real_order_id from real_order where state in ('CONFIRMED', 'FAILED', 'CANCELLED')";

    /** One table of the purge: its key column, the instant that ages it, an extra filter and which selection. */
    private record Target(String table, String key, String instant, String filter, boolean decisions) {}

    private static final List<Target> TARGETS = List.of(
            new Target(
                    "real_order_attempt",
                    "attempt_id",
                    "sent_at",
                    " and real_order_id in (" + FINAL_ORDERS + ")",
                    false),
            new Target(
                    "real_order",
                    "real_order_id",
                    "created_at",
                    " and state in ('CONFIRMED', 'FAILED', 'CANCELLED') and not exists (select 1 from"
                            + " real_order_attempt a where a.real_order_id = real_order.real_order_id)",
                    false),
            new Target("real_liquidation", "liquidation_id", "created_at", " and state in ('DONE', 'PARTIAL')", false),
            new Target(
                    "real_decision_request",
                    "decision_request_id",
                    "processed_at",
                    " and state in ('PROCESSED', 'DISCARDED')",
                    true));

    private final RiskRetentionDataSource retention;
    private final LockingTaskExecutor locks;
    private final RealExecutionMetrics metrics;
    private final Clock clock;
    private final String records;
    private final String decisions;

    @Autowired
    public RealExecutionPurgeJob(
            RiskRetentionDataSource retention, LockingTaskExecutor locks, RealExecutionMetrics metrics, Clock clock) {
        this(retention, locks, metrics, clock, RECORDS, DECISIONS);
    }

    /**
     * @param records the age of the selected orders, sends and liquidations, as a PostgreSQL interval
     * @param decisions the age of the selected closed decisions (tests prove the triggers with less)
     */
    RealExecutionPurgeJob(
            RiskRetentionDataSource retention,
            LockingTaskExecutor locks,
            RealExecutionMetrics metrics,
            Clock clock,
            String records,
            String decisions) {
        this.retention = retention;
        this.locks = locks;
        this.metrics = metrics;
        this.clock = clock;
        this.records = records;
        this.decisions = decisions;
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

    @Scheduled(cron = "0 30 4 * * *", zone = "UTC")
    public void scheduled() {
        runOnce();
    }

    public Round runOnce() {
        return CorrelationId.withNew(() -> {
            LockConfiguration lock = new LockConfiguration(clock.instant(), LOCK_NAME, LOCK_AT_MOST_FOR, Duration.ZERO);
            try {
                LockingTaskExecutor.TaskResult<Round> result = locks.executeWithLock(this::round, lock);
                if (!result.wasExecuted()) {
                    log.info("REAL execution purge skipped: another instance holds the lock");
                    return new Round(false, "", 0, 0, Optional.empty(), Optional.empty());
                }
                return result.getResult();
            } catch (RuntimeException | Error unexpected) {
                throw unexpected;
            } catch (Throwable checked) {
                throw new IllegalStateException("The REAL execution purge failed with a checked exception", checked);
            }
        });
    }

    private Round round() {
        Instant started = clock.instant();
        Instant deadline = started.plus(MAX_RUN);
        Instant cutoff = started;
        long deleted = 0;
        int batches = 0;
        try {
            cutoff = retention
                    .jdbc()
                    .sql("select now() - cast(:selection as interval)")
                    .param("selection", records)
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
                    .sql("delete from real_execution_purge_run where started_at < now() - interval '90 days'")
                    .update();
            UUID runId = record(started, cutoff, deleted, batches, SUCCEEDED, null);
            metrics.jobRun(RealExecutionMetrics.Job.PURGE);
            log.info(
                    "REAL execution purge finished: cutoff={}, deleted={}, batches={}, runLogCleaned={}",
                    cutoff,
                    deleted,
                    batches,
                    cleaned);
            return new Round(true, SUCCEEDED, deleted, batches, Optional.empty(), Optional.of(runId));
        } catch (RuntimeException failure) {
            String cause = failure.getClass().getSimpleName();
            log.error(
                    "REAL execution purge failed: cause={}, deletedBeforeFailure={}, batches={}",
                    cause,
                    deleted,
                    batches,
                    failure);
            Optional<UUID> runId = recordFailure(started, cutoff, deleted, batches, cause);
            return new Round(true, FAILED, deleted, batches, Optional.of(cause), runId);
        }
    }

    private int deleteBatch(Target target) {
        String sql = "delete from " + target.table() + " where " + target.key() + " in (select " + target.key()
                + " from " + target.table() + " where " + target.instant() + " < now() - cast(:selection as interval)"
                + target.filter() + " order by " + target.instant() + " limit :limit)";
        return retention
                .jdbc()
                .sql(sql)
                .param("selection", target.decisions() ? decisions : records)
                .param("limit", BATCH_SIZE)
                .update();
    }

    private UUID record(Instant started, Instant cutoff, long deleted, int batches, String result, String cause) {
        UUID runId = UUID.randomUUID();
        retention
                .jdbc()
                .sql("""
                        insert into real_execution_purge_run (run_id, started_at, finished_at, cutoff, deleted_count,
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
                    "REAL execution purge round could not be recorded: cause={}",
                    notRecorded.getClass().getSimpleName(),
                    notRecorded);
            return Optional.empty();
        }
    }
}
