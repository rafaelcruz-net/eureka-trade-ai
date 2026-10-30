package com.memetrade.audit.infra;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.audit.domain.AuditJob;
import com.memetrade.audit.domain.AuditJobResult;
import com.memetrade.audit.domain.AuditJobRunStore;
import com.memetrade.audit.domain.AuditRetentionPolicy;
import com.memetrade.audit.domain.BatchedDeletion.Outcome;
import com.memetrade.shared.web.CorrelationId;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Daily retention purge of the trail (F4, BR3.1 to BR3.3, NFR7.4). Deletes events with {@code occurredAt} before the
 * start of the UTC day minus {@code retentionMonths}, in batches of at most 1000, through the RETENTION pool only
 * (member of {@code audit_retention}; the database trigger also bounds the deletion to the 12-month window). Then it
 * publishes {@code AUDIT_RETENTION_PURGED} through the normal publisher and the MAIN pool, in a transaction of its own,
 * with the cutoff, the deleted count and the result, also when a batch failed or the time limit stopped it.
 *
 * <p>The only class that receives {@link AuditRetentionDataSource} (ArchUnit rule).
 */
@Component
public class AuditRetentionPurge extends AuditDailyJob {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionPurge.class);

    static final String TARGET_REF = "audit_event";

    private final AuditRetentionDataSource retention;
    private final AuditEventPublisher audit;
    private final TransactionTemplate ownTransaction;
    private final AuditRetentionPolicy policy;
    private final Clock clock;

    public AuditRetentionPurge(
            AuditRetentionDataSource retention,
            AuditEventPublisher audit,
            PlatformTransactionManager transactions,
            AuditJobRunStore runs,
            AuditJobMetrics metrics,
            AuditProperties properties,
            Clock clock) {
        super(AuditJob.AUDIT_RETENTION_PURGE, runs, metrics, properties.jobs(), clock);
        this.retention = retention;
        this.audit = audit;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.policy = new AuditRetentionPolicy(
                properties.jobs().retentionMonths(), properties.jobs().outboxDeliveredRetention());
        this.clock = clock;
    }

    /** Window of the database trigger, computed with the DATABASE clock (migration 0005). */
    static final String DATABASE_CUTOFF =
            "(date_trunc('day', now() at time zone 'UTC') - interval '12 months') at time zone 'UTC'";

    /**
     * The cutoff actually used and recorded: the earlier of the policy cutoff (injectable clock, BR3.1) and the
     * trigger's cutoff (database clock). Near UTC midnight a JVM clock ahead of the database would otherwise ask to
     * delete events the trigger still protects, and the whole run would end FAILED (review finding "purge cutoff
     * clock"). When the database cannot be asked, the policy cutoff is kept and the batch itself reports the error.
     */
    @Override
    protected Instant cutoff(Instant now) {
        Instant policyCutoff = policy.retentionCutoff(now);
        try {
            Instant databaseCutoff = retention
                    .jdbc()
                    .sql("select " + DATABASE_CUTOFF)
                    .query(Timestamp.class)
                    .single()
                    .toInstant();
            return databaseCutoff.isBefore(policyCutoff) ? databaseCutoff : policyCutoff;
        } catch (org.springframework.dao.DataAccessException unavailable) {
            log.warn("Database cutoff unavailable, the policy cutoff is used: cause={}", technicalCause(unavailable));
            return policyCutoff;
        }
    }

    @Override
    protected int deleteBatch(Instant cutoff, int limit) {
        // Auto-commit on the retention pool: each statement is its own transaction. The predicate repeats the
        // trigger's window in the same statement, so a midnight crossing between the cutoff query and this batch can
        // only make the deletion more conservative, never refused.
        return retention
                .jdbc()
                .sql("""
                        delete from audit_event
                        where event_id in (
                            select event_id from audit_event
                            where occurred_at < least(cast(:cutoff as timestamptz), %s)
                            order by occurred_at
                            limit :limit)
                        """.formatted(DATABASE_CUTOFF))
                .param("cutoff", Timestamp.from(cutoff))
                .param("limit", limit)
                .update();
    }

    /** BR3.3: one AUDIT_RETENTION_PURGED per run; a run whose event cannot be written is recorded as FAILED. */
    @Override
    protected AuditJobResult afterDeletion(Instant cutoff, Outcome outcome) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("cutoff", cutoff.toString());
        details.put("deletedCount", Long.toString(outcome.deleted()));
        details.put("result", outcome.result().name());
        try {
            ownTransaction.executeWithoutResult(status -> audit.publish(AuditEventCommand.system(
                    AuditActions.AUDIT_RETENTION_PURGED,
                    TARGET_REF,
                    CorrelationId.current(),
                    clock.instant(),
                    details)));
            return outcome.result();
        } catch (RuntimeException notRecorded) {
            log.error(
                    "AUDIT_RETENTION_PURGED could not be recorded: job={}, result={}, cause={}",
                    job(),
                    outcome.result(),
                    technicalCause(notRecorded));
            return AuditJobResult.FAILED;
        }
    }
}
