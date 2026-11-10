package com.memetrade.identity.infra;

import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.identity.domain.purge.IdentityPurge;
import com.memetrade.identity.domain.purge.PurgeOutcome;
import com.memetrade.identity.domain.purge.PurgeResult;
import com.memetrade.identity.domain.purge.PurgeRun;
import com.memetrade.identity.domain.purge.PurgeRunStore;
import com.memetrade.shared.web.CorrelationId;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor.TaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Common executor of the Identity purges (u2-retention, reliability-design.md NFR7.1 to NFR7.4, scalability-design.md
 * NFR3.7, observability-design.md NFR8.1 to NFR8.4). Single entry point of the schedule and of the startup recovery:
 *
 * <ol>
 *   <li>ShedLock lock named by the purge ({@code lockAtMostFor} 35 min, {@code lockAtLeastFor} 5 min, database time);
 *       without the lock it returns at once, nothing deleted;
 *   <li>the cutoff from the injectable clock; batches of at most {@code batch-size} rows by primary key, each in a
 *       transaction of its own ({@code REQUIRES_NEW}), until a batch comes back short; a failed batch stops the loop and
 *       the earlier batches stay deleted;
 *   <li>the {@code *_PURGED} event by {@code system} (counts only, no tenant) and the {@code identity_job_run} row in
 *       ONE short {@code REQUIRES_NEW} transaction, so the count and the record agree;
 *   <li>metrics, and logs with totals, duration and the error class only; the run has its own correlation id.
 * </ol>
 *
 * Nothing propagates to the scheduler or to the api: every exception of a run becomes a {@code FAILED} outcome.
 */
@Component
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class IdentityJobRunner {

    private static final Logger log = LoggerFactory.getLogger(IdentityJobRunner.class);

    private final LockingTaskExecutor locks;
    private final Map<IdentityPurge, IdentityPurgeJob> jobs = new EnumMap<>(IdentityPurge.class);
    private final PurgeRunStore runs;
    private final AuditEventPublisher audit;
    private final TransactionTemplate ownTransaction;
    private final IdentityJobMetrics metrics;
    private final IdentityPurgeProperties settings;
    private final Clock clock;

    public IdentityJobRunner(
            LockingTaskExecutor locks,
            List<IdentityPurgeJob> purgeJobs,
            PurgeRunStore runs,
            AuditEventPublisher audit,
            PlatformTransactionManager transactions,
            IdentityJobMetrics metrics,
            IdentityPurgeProperties settings,
            Clock clock) {
        this.locks = locks;
        purgeJobs.forEach(job -> jobs.put(job.purge(), job));
        this.runs = runs;
        this.audit = audit;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.metrics = metrics;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Runs {@code purge} when its lock is free.
     *
     * @return the outcome of the run, or empty when another run holds the lock
     * @throws DataAccessException when the lock itself cannot be read (the schedule logs it and goes on)
     */
    public Optional<PurgeOutcome> runLocked(IdentityPurge purge) {
        IdentityPurgeJob job = jobs.get(purge);
        if (job == null) {
            throw new IllegalArgumentException("No job is registered for the purge " + purge.purgeId());
        }
        LockConfiguration lock = new LockConfiguration(
                clock.instant(), purge.purgeId(), settings.lockAtMostFor(), settings.lockAtLeastFor());
        try {
            TaskResult<PurgeOutcome> result = locks.executeWithLock(() -> CorrelationId.withNew(() -> run(job)), lock);
            if (!result.wasExecuted()) {
                log.debug("Purge skipped, another run holds the lock: purge={}", purge.purgeId());
                return Optional.empty();
            }
            return Optional.of(result.getResult());
        } catch (RuntimeException | Error unexpected) {
            throw unexpected;
        } catch (Throwable checked) {
            throw new IllegalStateException("Purge " + purge.purgeId() + " failed with a checked exception", checked);
        }
    }

    /** Same as {@link #runLocked(IdentityPurge)} for the schedule: a lock-store failure is logged, never propagated. */
    public void runScheduled(IdentityPurge purge) {
        try {
            runLocked(purge);
        } catch (DataAccessException unavailable) {
            log.error("Purge could not start: purge={}, cause={}", purge.purgeId(), technicalCause(unavailable));
        }
    }

    private PurgeOutcome run(IdentityPurgeJob job) {
        Instant startedAt = clock.instant();
        Instant cutoff = job.cutoff(startedAt);
        Loop loop = new Loop();
        try {
            deleteAll(job, cutoff, loop);
        } catch (RuntimeException failed) {
            // Explicit FAILED result: the batches done stay deleted and the next run tries again (BR2.7).
            loop.failure = failed;
        }
        PurgeResult result = loop.failure == null ? PurgeResult.SUCCEEDED : PurgeResult.FAILED;
        result = finish(job, startedAt, cutoff, result, loop);
        Instant endedAt = clock.instant();
        metrics.finished(job.purge(), result, loop.deleted, endedAt);
        logEnd(job.purge(), result, loop, Duration.between(startedAt, endedAt));
        return new PurgeOutcome(job.purge(), result, cutoff, loop.deleted, loop.related, loop.batches);
    }

    private void deleteAll(IdentityPurgeJob job, Instant cutoff, Loop loop) {
        int limit = settings.batchSize();
        while (true) {
            Instant batchStart = clock.instant();
            loop.batches++;
            IdentityPurgeJob.BatchCount count = ownTransaction.execute(status -> job.deleteBatch(cutoff, limit));
            Duration took = Duration.between(batchStart, clock.instant());
            metrics.batch(job.purge(), took);
            loop.deleted += count.rows();
            loop.related += count.related();
            log.debug(
                    "Purge batch: purge={}, deleted={}, related={}, durationMs={}",
                    job.purge().purgeId(),
                    count.rows(),
                    count.related(),
                    took.toMillis());
            if (count.rows() < limit) {
                return;
            }
        }
    }

    /** The event and the run row together; a failure here is logged and turns the run into FAILED. */
    private PurgeResult finish(IdentityPurgeJob job, Instant startedAt, Instant cutoff, PurgeResult result, Loop loop) {
        try {
            ownTransaction.executeWithoutResult(status -> {
                audit.publish(AuditEventCommand.system(
                        job.purge().action(),
                        "purge:" + job.purge().purgeId(),
                        CorrelationId.current(),
                        clock.instant(),
                        details(job, result, loop)));
                runs.record(new PurgeRun(
                        job.purge(),
                        startedAt,
                        result,
                        result == PurgeResult.SUCCEEDED ? Optional.of(clock.instant()) : Optional.empty(),
                        cutoff,
                        loop.deleted));
            });
            return result;
        } catch (RuntimeException notRecorded) {
            log.error(
                    "Purge run could not be recorded: purge={}, result={}, deletedSoFar={}, cause={}",
                    job.purge().purgeId(),
                    result,
                    loop.deleted,
                    technicalCause(notRecorded));
            return PurgeResult.FAILED;
        }
    }

    private static Map<String, String> details(IdentityPurgeJob job, PurgeResult result, Loop loop) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("deleted", Long.toString(loop.deleted));
        details.put("batches", Integer.toString(loop.batches));
        job.relatedDetail().ifPresent(key -> details.put(key, Long.toString(loop.related)));
        details.put("result", result.name());
        return details;
    }

    private void logEnd(IdentityPurge purge, PurgeResult result, Loop loop, Duration took) {
        if (result == PurgeResult.SUCCEEDED) {
            log.info(
                    "Purge finished: purge={}, deleted={}, related={}, batches={}, durationMs={}",
                    purge.purgeId(),
                    loop.deleted,
                    loop.related,
                    loop.batches,
                    took.toMillis());
        } else {
            log.error(
                    "Purge failed: purge={}, deletedSoFar={}, batches={}, durationMs={}, cause={}",
                    purge.purgeId(),
                    loop.deleted,
                    loop.batches,
                    took.toMillis(),
                    loop.failure == null ? "not recorded" : technicalCause(loop.failure));
        }
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

    /** Running totals of one run. */
    private static final class Loop {
        private long deleted;
        private long related;
        private int batches;
        private RuntimeException failure;
    }
}
