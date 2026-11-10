package com.memetrade.audit.infra;

import com.memetrade.audit.api.AuditEventCommand;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Relay of the AuditTrail (runs only in the API process): reserves up to a batch of pending outbox rows in creation
 * order with {@code FOR UPDATE SKIP LOCKED}, inserts each into {@code audit_event} by {@code event_id} (a repeated id
 * is discarded) with {@code recorded_at} from the injectable clock, and marks the rows delivered, all in one
 * transaction. A crash before commit leaves the rows pending for the next cycle: at-least-once delivery plus
 * deduplication gives exactly one event in the trail (BR1.1).
 *
 * <p>U2: when commit timestamps are tracked, the reservation also reads the commit instant of the transaction that
 * inserted each row ({@code pg_xact_commit_timestamp(xmin)}); after this batch commits, each delivery becomes a
 * sample of {@code audit_delivery_seconds} (NFR4.2).
 */
@Component
public class AuditOutboxRelay {

    private static final String RESERVE = """
            select event_id, payload::text as payload, %s as committed_at
            from audit_outbox
            where delivered_at is null
            order by created_at, event_id
            limit :batch
            for update skip locked
            """;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AuditProperties properties;
    private final AuditDeliveryMetrics metrics;

    public AuditOutboxRelay(JdbcClient jdbc, Clock clock, AuditProperties properties, AuditDeliveryMetrics metrics) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
        this.metrics = metrics;
    }

    /** Delivers one batch; returns how many outbox rows were marked delivered. */
    @Transactional
    public int relayBatch() {
        boolean measure = metrics.commitTimestampsTracked();
        List<PendingRow> pending = jdbc.sql(RESERVE.formatted(measure ? "pg_xact_commit_timestamp(xmin)" : "null"))
                .param("batch", properties.relayBatchSize())
                .query((rs, n) -> {
                    Timestamp committed = rs.getTimestamp("committed_at");
                    return new PendingRow(
                            rs.getObject("event_id", UUID.class),
                            rs.getString("payload"),
                            Optional.ofNullable(committed).map(Timestamp::toInstant));
                })
                .list();
        Instant now = clock.instant();
        List<Optional<Instant>> commits = new ArrayList<>();
        int recorded = 0;
        for (PendingRow row : pending) {
            recorded += insertIntoTrail(AuditPayloadCodec.decode(row.payload()), now);
            jdbc.sql("update audit_outbox set delivered_at = :now where event_id = :id")
                    .param("now", Timestamp.from(now))
                    .param("id", row.eventId())
                    .update();
            commits.add(row.committedAt());
        }
        afterCommit(measure, commits, now, recorded);
        return pending.size();
    }

    /** Pending count and age of the oldest pending row, for metrics and the degraded health state. */
    @Transactional(readOnly = true)
    public OutboxLag lag() {
        Instant now = clock.instant();
        return jdbc.sql(
                        "select count(*) as pending, min(created_at) as oldest from audit_outbox where delivered_at is null")
                .query((rs, n) -> {
                    Timestamp oldest = rs.getTimestamp("oldest");
                    Duration age = oldest == null ? Duration.ZERO : Duration.between(oldest.toInstant(), now);
                    return new OutboxLag(rs.getLong("pending"), age.isNegative() ? Duration.ZERO : age);
                })
                .single();
    }

    private void afterCommit(boolean measure, List<Optional<Instant>> commits, Instant recordedAt, int recorded) {
        if (commits.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                metrics.recorded(recorded);
                if (measure) {
                    commits.forEach(committedAt -> metrics.delivered(committedAt, recordedAt));
                }
            }
        });
    }

    /**
     * Returns 1 when the event was written, 0 when its {@code event_id} was already in the trail. u2-retention
     * (BR2.18): the {@code walletHmac} of the payload goes to {@code wallet_hmac}; without it the column stays null.
     */
    private int insertIntoTrail(DecodedAuditEvent decoded, Instant recordedAt) {
        AuditEventCommand event = decoded.command();
        return jdbc.sql("""
                        insert into audit_event
                          (event_id, tenant_id, actor, actor_type, action, target_ref, correlation_id, occurred_at,
                           recorded_at, details, wallet_hmac)
                        values (:id, :tenant, :actor, :actorType, :action, :target, :correlation, :occurredAt,
                                :recordedAt, cast(:details as jsonb), :walletHmac)
                        on conflict (event_id) do nothing
                        """)
                .param("walletHmac", decoded.walletHmac().orElse(null))
                .param("id", event.eventId())
                .param("tenant", event.tenantId().orElse(null))
                .param("actor", event.actor())
                .param("actorType", event.actorType().name())
                .param("action", event.action())
                .param("target", event.targetRef())
                .param("correlation", event.correlationId())
                .param("occurredAt", Timestamp.from(event.occurredAt()))
                .param("recordedAt", Timestamp.from(recordedAt))
                .param("details", AuditPayloadCodec.encodeDetails(event))
                .update();
    }

    private record PendingRow(UUID eventId, String payload, Optional<Instant> committedAt) {}

    /** Outbox delivery lag. */
    public record OutboxLag(long pending, Duration oldestAge) {
        public Optional<Duration> oldest() {
            return pending == 0 ? Optional.empty() : Optional.of(oldestAge);
        }
    }
}
