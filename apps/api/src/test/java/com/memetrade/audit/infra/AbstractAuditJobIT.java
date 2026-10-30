package com.memetrade.audit.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.domain.AuditJob;
import com.memetrade.testsupport.AbstractPostgresIT;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Base of the daily-job integration tests. Every test starts without job state and without ShedLock rows (the lock's
 * 5-minute minimum would otherwise skip the next test's run), creates its own events and checks them by id, because
 * the shared database may hold old events written by other test classes.
 */
abstract class AbstractAuditJobIT extends AbstractPostgresIT {

    @Autowired
    protected ObjectMapper json;

    @BeforeEach
    void clearJobState() {
        // Expire the locks instead of deleting them: ShedLock remembers which lock rows exist and then only UPDATEs.
        jdbc.sql("update shedlock set lock_until = now() - interval '1 second'").update();
        jdbc.sql("delete from audit_job_run").update();
    }

    protected long heldLocks() {
        return jdbc.sql("select count(*) from shedlock where lock_until > now()")
                .query(Long.class)
                .single();
    }

    /** The default test clock instant 2026-10-28T12:00Z gives the cutoff 2025-10-28T00:00Z. */
    protected static final Instant CUTOFF = Instant.parse("2025-10-28T00:00:00Z");

    protected UUID insertEvent(Instant occurredAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into audit_event (event_id, tenant_id, actor, actor_type, action, correlation_id,
                                                 occurred_at, recorded_at, details)
                        values (:id, null, 'system', 'SYSTEM', 'JOB_IT_ACTION', 'job-it', :at, :at, '{}'::jsonb)
                        """).param("id", id).param("at", Timestamp.from(occurredAt)).update();
        return id;
    }

    protected boolean eventExists(UUID id) {
        return jdbc.sql("select count(*) from audit_event where event_id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single()
                == 1L;
    }

    protected long eventsBefore(Instant cutoff) {
        return jdbc.sql("select count(*) from audit_event where occurred_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .query(Long.class)
                .single();
    }

    /** Outbox row with a valid payload (the relay of other tests may deliver it). */
    protected UUID insertOutbox(Instant createdAt, Instant deliveredAt) {
        AuditEventCommand event = AuditEventCommand.system("JOB_IT_OUTBOX", null, "job-it", createdAt, Map.of());
        jdbc.sql("""
                        insert into audit_outbox (event_id, payload, created_at, delivered_at)
                        values (:id, cast(:payload as jsonb), :created, :delivered)
                        """)
                .param("id", event.eventId())
                .param("payload", AuditPayloadCodec.encode(event))
                .param("created", Timestamp.from(createdAt))
                .param("delivered", deliveredAt == null ? null : Timestamp.from(deliveredAt))
                .update();
        return event.eventId();
    }

    protected boolean outboxExists(UUID id) {
        return jdbc.sql("select count(*) from audit_outbox where event_id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single()
                == 1L;
    }

    protected void deleteOutbox(UUID id) {
        jdbc.sql("delete from audit_outbox where event_id = :id")
                .param("id", id)
                .update();
    }

    /** Ids of the AUDIT_RETENTION_PURGED rows now in the outbox (to diff before and after a run). */
    protected Set<UUID> purgedEventIds() {
        return new HashSet<>(
                jdbc.sql("select event_id from audit_outbox where payload->>'action' = 'AUDIT_RETENTION_PURGED'")
                        .query(UUID.class)
                        .list());
    }

    /** Payloads of the AUDIT_RETENTION_PURGED events published since {@code before} was taken. */
    protected List<JsonNode> purgedEventsSince(Set<UUID> before) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (UUID id : purgedEventIds()) {
            if (!before.contains(id)) {
                events.add(json.readTree(jdbc.sql("select payload::text from audit_outbox where event_id = :id")
                        .param("id", id)
                        .query(String.class)
                        .single()));
            }
        }
        return events;
    }

    /** Settings of application.yml with a reduced batch size and time limit, for tests only. */
    protected static AuditProperties reducedSettings(int batchSize, Duration timeLimit) {
        AuditProperties.Jobs defaults = AuditProperties.Jobs.defaults();
        return new AuditProperties(
                Duration.ofSeconds(1),
                100,
                Duration.ofSeconds(60),
                new AuditProperties.Jobs(
                        defaults.retentionMonths(),
                        defaults.outboxDeliveredRetention(),
                        defaults.retentionCron(),
                        defaults.outboxCleanupCron(),
                        timeLimit,
                        batchSize,
                        defaults.lockAtMostFor(),
                        defaults.lockAtLeastFor(),
                        defaults.recoverAfter(),
                        defaults.degradedAfter()));
    }

    protected String lastResult(AuditJob job) {
        return jdbc.sql("select last_result from audit_job_run where job_name = :job")
                .param("job", job.name())
                .query(String.class)
                .single();
    }
}
