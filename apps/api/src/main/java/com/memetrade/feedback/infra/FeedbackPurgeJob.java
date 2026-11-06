package com.memetrade.feedback.infra;

import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.web.CorrelationId;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The daily purge of the comments older than 180 days (BR6.7, NFR2.6), at 03:00 UTC, once across instances (ShedLock
 * {@value #LOCK_NAME}), only in the API process and only through the retention pool (the only class that receives
 * {@link FeedbackRetentionDataSource}, ArchUnit rule): the trigger of migration 0011 refuses a DELETE by any login that
 * is not a member of {@code feedback_retention}.
 *
 * <ol>
 *   <li>the cut is the injectable clock minus the retention (180 days);
 *   <li>each batch deletes at most {@code purge-batch-size} (1000) comments by {@code feedback_id}, in auto-commit (one
 *       transaction per batch), until a batch comes back short;
 *   <li>one {@code FEEDBACK_PURGED} by {@code system} with the count, through the main pool; the log carries totals only,
 *       never a text, a tenant or a person;
 *   <li>a failure keeps the batches done, is logged and is retried the next day; the health indicator
 *       {@code feedbackPurge} turns DOWN when the last success is older than 48 hours.
 * </ol>
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class FeedbackPurgeJob {

    public static final String LOCK_NAME = "feedbackPurge";
    public static final String FEEDBACK_PURGED = "FEEDBACK_PURGED";

    private static final Logger log = LoggerFactory.getLogger(FeedbackPurgeJob.class);

    private final FeedbackRetentionDataSource retention;
    private final LockingTaskExecutor locks;
    private final AuditEventPublisher audit;
    private final TransactionTemplate transactions;
    private final FeedbackProperties settings;
    private final Clock clock;
    private final Instant startedAt;
    private final AtomicReference<Instant> lastSuccessAt = new AtomicReference<>();

    public FeedbackPurgeJob(
            FeedbackRetentionDataSource retention,
            LockingTaskExecutor locks,
            AuditEventPublisher audit,
            PlatformTransactionManager transactionManager,
            FeedbackProperties settings,
            Clock clock) {
        this.retention = retention;
        this.locks = locks;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.settings = settings;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    /**
     * One round of the purge.
     *
     * @param ran false when another instance held the round
     * @param succeeded false when the round failed (the batches done stay deleted)
     */
    public record Round(boolean ran, boolean succeeded, long deleted, int batches, Instant cutoff) {}

    @Scheduled(cron = "${memetrade.admin.feedback.purge-cron}", zone = "UTC")
    public void scheduled() {
        runOnce();
    }

    public Round runOnce() {
        return CorrelationId.withNew(() -> {
            LockConfiguration lock =
                    new LockConfiguration(clock.instant(), LOCK_NAME, settings.purgeLockAtMostFor(), Duration.ZERO);
            try {
                LockingTaskExecutor.TaskResult<Round> result = locks.executeWithLock(this::round, lock);
                if (!result.wasExecuted()) {
                    log.info("Feedback purge skipped: another instance holds the round");
                    return new Round(false, false, 0, 0, clock.instant());
                }
                return result.getResult();
            } catch (RuntimeException | Error unexpected) {
                throw unexpected;
            } catch (Throwable checked) {
                throw new IllegalStateException("The feedback purge failed", checked);
            }
        });
    }

    private Round round() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(settings.retention());
        long deleted = 0;
        int batches = 0;
        try {
            while (true) {
                int batch = deleteBatch(cutoff);
                batches++;
                deleted += batch;
                if (batch < settings.purgeBatchSize()) {
                    break;
                }
            }
            long total = deleted;
            transactions.executeWithoutResult(status -> audit.publish(AuditEventCommand.system(
                    FEEDBACK_PURGED,
                    "feedback",
                    CorrelationId.current(),
                    clock.instant(),
                    Map.of("deleted", Long.toString(total), "cutoff", cutoff.toString()))));
            lastSuccessAt.set(clock.instant());
            log.info("Feedback purge finished: cutoff={}, deleted={}, batches={}", cutoff, deleted, batches);
            return new Round(true, true, deleted, batches, cutoff);
        } catch (RuntimeException failure) {
            log.error(
                    "Feedback purge failed: cause={}, deletedBeforeFailure={}, batches={}",
                    failure.getClass().getSimpleName(),
                    deleted,
                    batches,
                    failure);
            return new Round(true, false, deleted, batches, cutoff);
        }
    }

    /** One batch in its own transaction (auto-commit) through the retention login. */
    private int deleteBatch(Instant cutoff) {
        return retention
                .jdbc()
                .sql("""
                        delete from feedback_entry
                        where feedback_id in (
                            select feedback_id from feedback_entry
                            where created_at < :cutoff
                            order by created_at
                            limit :limit)
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .param("limit", settings.purgeBatchSize())
                .update();
    }

    /** The last successful round, or empty before the first one (read by the health indicator). */
    public Optional<Instant> lastSuccessAt() {
        return Optional.ofNullable(lastSuccessAt.get());
    }

    /** When this process started: the reference of the health indicator before the first success. */
    public Instant startedAt() {
        return startedAt;
    }
}
