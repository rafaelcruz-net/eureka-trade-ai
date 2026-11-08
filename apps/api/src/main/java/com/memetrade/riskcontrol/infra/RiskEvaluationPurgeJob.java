package com.memetrade.riskcontrol.infra;

import com.memetrade.shared.web.CorrelationId;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily purge of the old approved evaluations (BR4.3 as amended by the NFR Design; NFR2.5, NFR10.6), at 03:30 UTC,
 * once across instances (ShedLock {@code riskcontrol-purge}, at most 45 minutes), through the U6 retention pool ONLY
 * (the only class that receives {@link RiskRetentionDataSource}, ArchUnit rule).
 *
 * <ol>
 *   <li>Before the first batch, the round warms the path of a batch once ({@link #warmUp()}: the DELETE of batch 1 in a
 *       transaction that is rolled back), outside the measured time of every batch. Batch times are logged in whole
 *       milliseconds rounded up.
 *   <li>Each batch selects at most {@code batch-size} APPROVED evaluations with {@code evaluated_at} before the
 *       DATABASE {@code now()} minus 31 days that no rejection cites (partial index plus NOT EXISTS on the index of
 *       {@code order_rejection.evaluation_id}) and deletes them in the same statement, in auto-commit: one transaction
 *       per batch. The trigger checks again role, outcome and the 30 days; the day of margin makes every selected row
 *       pass it.
 *   <li>Batches repeat until one comes back short or {@code max-run} passed; the rest waits for the next day.
 *   <li>Every round, succeeded or failed, writes one row of {@code risk_purge_run} (no person data) and deletes the
 *       rows of that log older than 90 days; a failure keeps the batches already done and counts
 *       {@code riskcontrol.purge.errors}. No audit event (Q2 of the NFR Design); the log carries totals only.
 * </ol>
 */
@Component
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class RiskEvaluationPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RiskEvaluationPurgeJob.class);

    static final String LOCK_NAME = "riskcontrol-purge";
    static final String SUCCEEDED = "SUCCEEDED";
    static final String FAILED = "FAILED";

    /** One batch: the selection of the purge (scripts/load/u6-plans.mjs mirrors it) and its delete in one statement. */
    private static final String DELETE_BATCH = """
            delete from order_evaluation
            where evaluation_id in (
                select e.evaluation_id from order_evaluation e
                where e.outcome = 'APPROVED'
                  and e.evaluated_at < now() - make_interval(secs => :age)
                  and not exists (select 1 from order_rejection r where r.evaluation_id = e.evaluation_id)
                order by e.evaluated_at
                limit :limit)
            """;

    private final RiskRetentionDataSource retention;
    private final LockingTaskExecutor locks;
    private final RiskControlMetrics metrics;
    private final RiskControlProperties.Purge settings;
    private final Clock clock;

    public RiskEvaluationPurgeJob(
            RiskRetentionDataSource retention,
            LockingTaskExecutor locks,
            RiskControlMetrics metrics,
            RiskControlProperties properties,
            Clock clock) {
        this.retention = retention;
        this.locks = locks;
        this.metrics = metrics;
        this.settings = properties.purge();
        this.clock = clock;
    }

    /**
     * One round of the purge.
     *
     * @param ran false when another instance held the lock (nothing done, nothing recorded)
     * @param result {@code SUCCEEDED} or {@code FAILED}; empty text when the round did not run
     * @param runId the row of {@code risk_purge_run} written by the round
     * @param cutoff the database now() minus the selection age, recorded with the round
     * @param warmUp the time of the warm-up, zero when it did not finish
     * @param batchDurations the measured time of each batch, in order (the batch log lines show it rounded up to the
     *     millisecond)
     */
    public record Round(
            boolean ran,
            String result,
            long deleted,
            int batches,
            Optional<String> failureCause,
            Optional<UUID> runId,
            Instant cutoff,
            Duration warmUp,
            List<Duration> batchDurations) {

        static Round skipped(Instant at) {
            return new Round(false, "", 0, 0, Optional.empty(), Optional.empty(), at, Duration.ZERO, List.of());
        }
    }

    @Scheduled(cron = "${memetrade.riskcontrol.purge.cron}", zone = "UTC")
    public void scheduled() {
        runOnce();
    }

    /** Runs a round now through the lock (the scheduled entry point and the trigger of the load test). */
    public Round runOnce() {
        return CorrelationId.withNew(() -> {
            LockConfiguration lock =
                    new LockConfiguration(clock.instant(), LOCK_NAME, settings.lockAtMostFor(), Duration.ZERO);
            try {
                LockingTaskExecutor.TaskResult<Round> result = locks.executeWithLock(this::round, lock);
                if (!result.wasExecuted()) {
                    log.info("Risk purge skipped: another instance holds the lock");
                    return Round.skipped(clock.instant());
                }
                return result.getResult();
            } catch (RuntimeException | Error unexpected) {
                throw unexpected;
            } catch (Throwable checked) {
                throw new IllegalStateException("The risk purge round failed with a checked exception", checked);
            }
        });
    }

    private Round round() {
        Instant started = clock.instant();
        Instant deadline = started.plus(settings.maxRun());
        Instant cutoff = started;
        long deleted = 0;
        int batches = 0;
        Duration warmedUp = Duration.ZERO;
        List<Duration> durations = new ArrayList<>();
        try {
            cutoff = databaseCutoff();
            long warmUpStarted = System.nanoTime();
            warmUp();
            warmedUp = Duration.ofNanos(System.nanoTime() - warmUpStarted);
            log.info("Risk purge warm-up: durationMs={}", roundedUpMillis(warmedUp));
            log.info("Risk purge started: cutoff={}, batchSize={}", cutoff, settings.batchSize());
            while (clock.instant().isBefore(deadline)) {
                long batchStarted = System.nanoTime();
                int batch = deleteBatch(settings.batchSize());
                Duration duration = Duration.ofNanos(System.nanoTime() - batchStarted);
                batches++;
                deleted += batch;
                durations.add(duration);
                log.info(
                        "Risk purge batch: batch={}, deleted={}, durationMs={}",
                        batches,
                        batch,
                        roundedUpMillis(duration));
                if (batch < settings.batchSize()) {
                    break;
                }
            }
            int cleaned = cleanRunLog();
            UUID runId = record(started, cutoff, deleted, batches, SUCCEEDED, null);
            log.info(
                    "Risk purge finished: start={}, end={}, cutoff={}, deleted={}, batches={}, runLogCleaned={}",
                    started,
                    clock.instant(),
                    cutoff,
                    deleted,
                    batches,
                    cleaned);
            return new Round(
                    true,
                    SUCCEEDED,
                    deleted,
                    batches,
                    Optional.empty(),
                    Optional.of(runId),
                    cutoff,
                    warmedUp,
                    List.copyOf(durations));
        } catch (RuntimeException failure) {
            String cause = failure.getClass().getSimpleName();
            metrics.purgeFailed();
            log.error(
                    "Risk purge failed: cause={}, deletedBeforeFailure={}, batches={}",
                    cause,
                    deleted,
                    batches,
                    failure);
            Optional<UUID> runId = recordFailure(started, cutoff, deleted, batches, cause);
            return new Round(
                    true,
                    FAILED,
                    deleted,
                    batches,
                    Optional.of(cause),
                    runId,
                    cutoff,
                    warmedUp,
                    List.copyOf(durations));
        }
    }

    /**
     * The warm-up of a round (Build and Test loops 2 and 3, F10, NFR2.5), once, before the first batch and outside the
     * measured time of every batch.
     *
     * <p>What it does: through the retention connection it opens one transaction of its own, executes the very DELETE of
     * batch 1 (same cut, same limit of {@code batch-size} rows) and rolls it back. So the connection is open, and the
     * row triggers of {@code order_evaluation}, the foreign-key checks of the tables that reference it, the partial
     * index {@code order_evaluation_purge_idx}, the index of {@code order_rejection.evaluation_id} and the pages of
     * batch 1 have all run once in this session. Nothing is deleted: the transaction is never committed. Its row locks
     * last only while it runs, inside the lock {@code riskcontrol-purge} of the round; the login gains no privilege.
     *
     * <p>What it does NOT guarantee: that batch 1 costs the same as the next ones. The rolled-back DELETE leaves its
     * rows marked by an aborted transaction that batch 1 still has to check, the cache can still be evicted between the
     * warm-up and batch 1, and the database can be busy at that moment; batch 1 is measured and checked against the same limit as every
     * other batch. A failure here (connection, trigger, lock) fails the round like any other: FAILED in
     * {@code risk_purge_run} with the class of the exception, nothing deleted, no batch started.
     */
    protected void warmUp() {
        retention.inRolledBackTransaction(transaction -> transaction
                .sql(DELETE_BATCH)
                .param("age", settings.selectionAge().toSeconds())
                .param("limit", settings.batchSize())
                .update());
    }

    /**
     * A duration in whole milliseconds ROUNDED UP (loop 3, F10): 100 ms and 1 microsecond is 101 ms, so the limit of
     * 100 ms per batch is never passed by truncation.
     */
    static long roundedUpMillis(Duration duration) {
        return Math.ceilDiv(duration.toNanos(), 1_000_000L);
    }

    /** One batch in its own transaction (auto-commit); the cut is the database now() of this very statement. */
    protected int deleteBatch(int limit) {
        return retention
                .jdbc()
                .sql(DELETE_BATCH)
                .param("age", settings.selectionAge().toSeconds())
                .param("limit", limit)
                .update();
    }

    private Instant databaseCutoff() {
        return retention
                .jdbc()
                .sql("select now() - make_interval(secs => :age)")
                .param("age", settings.selectionAge().toSeconds())
                .query(Timestamp.class)
                .single()
                .toInstant();
    }

    /** The log is insert only; the round itself deletes its rows older than 90 days (the trigger bounds it). */
    private int cleanRunLog() {
        return retention
                .jdbc()
                .sql("delete from risk_purge_run where started_at < now() - interval '90 days'")
                .update();
    }

    private UUID record(Instant started, Instant cutoff, long deleted, int batches, String result, String cause) {
        UUID runId = UUID.randomUUID();
        retention
                .jdbc()
                .sql("""
                        insert into risk_purge_run (run_id, started_at, finished_at, cutoff, deleted_count, batches,
                            result, failure_cause)
                        values (:id, :started, :finished, :cutoff, :deleted, :batches, :result, :cause)
                        """)
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

    /** A failed round is recorded too; when even that write fails, the error log is the only evidence. */
    private Optional<UUID> recordFailure(Instant started, Instant cutoff, long deleted, int batches, String cause) {
        try {
            return Optional.of(record(started, cutoff, deleted, batches, FAILED, cause));
        } catch (RuntimeException notRecorded) {
            log.error(
                    "Risk purge round could not be recorded: cause={}",
                    notRecorded.getClass().getSimpleName(),
                    notRecorded);
            return Optional.empty();
        }
    }
}
