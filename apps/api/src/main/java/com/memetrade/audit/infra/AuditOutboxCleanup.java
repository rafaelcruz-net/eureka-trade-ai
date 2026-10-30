package com.memetrade.audit.infra;

import com.memetrade.audit.domain.AuditJob;
import com.memetrade.audit.domain.AuditJobRunStore;
import com.memetrade.audit.domain.AuditRetentionPolicy;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Daily cleanup of the outbox (F5, BR3.4, NFR4.3): deletes rows delivered more than 7 days ago, in batches of at most
 * 1000, through the main pool. A pending row ({@code delivered_at} empty) is never selected, whatever its age; its
 * lag shows in the {@code auditRelay} health and in {@code audit_outbox_pending}. Same limit, state row and metrics as
 * the purge; no audit event (the outbox is a technical buffer, not the trail).
 */
@Component
public class AuditOutboxCleanup extends AuditDailyJob {

    private final JdbcClient jdbc;
    private final AuditRetentionPolicy policy;

    public AuditOutboxCleanup(
            JdbcClient jdbc, AuditJobRunStore runs, AuditJobMetrics metrics, AuditProperties properties, Clock clock) {
        super(AuditJob.AUDIT_OUTBOX_CLEANUP, runs, metrics, properties.jobs(), clock);
        this.jdbc = jdbc;
        this.policy = new AuditRetentionPolicy(
                properties.jobs().retentionMonths(), properties.jobs().outboxDeliveredRetention());
    }

    @Override
    protected Instant cutoff(Instant now) {
        return policy.outboxCutoff(now);
    }

    @Override
    protected int deleteBatch(Instant cutoff, int limit) {
        // Called outside any transaction: auto-commit makes each batch its own transaction.
        return jdbc.sql("""
                        delete from audit_outbox
                        where event_id in (
                            select event_id from audit_outbox
                            where delivered_at is not null and delivered_at < :cutoff
                            order by delivered_at
                            limit :limit)
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .param("limit", limit)
                .update();
    }
}
