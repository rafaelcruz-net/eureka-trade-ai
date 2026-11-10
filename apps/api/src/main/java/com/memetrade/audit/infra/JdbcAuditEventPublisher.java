package com.memetrade.audit.infra;

import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.audit.api.AuditPublishRejectedException;
import com.memetrade.audit.api.AuditPublishRejectedException.Reason;
import com.memetrade.audit.domain.AuditEventValidator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes audit events to {@code audit_outbox} inside the caller's transaction (C9, BR1.1). Before writing it refuses
 * an event without an active transaction or with an invalid field or a forbidden detail (BR1.2, BR1.4); the refusal
 * propagates and the caller's action rolls back (NFR4.7). Each refusal increments
 * {@code audit_publish_rejected_total{reason}} and is logged at ERROR with action, reason and field, never the value.
 * {@code audit_events_published_total{action}} counts only events whose transaction committed.
 *
 * <p>u2-retention (BR2.1 to BR2.3, J6): an event with a wallet gets {@code walletHmac} in its payload, from the
 * {@link WalletHasher}; the actor stays masked and the full address never reaches the outbox. A full address in
 * {@code details} or as the actor is refused with {@code FULL_WALLET}, logged without the value
 * ({@code AUDIT_FULL_WALLET_REJECTED} of monitoring-design.md).
 */
@Component
public class JdbcAuditEventPublisher implements AuditEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(JdbcAuditEventPublisher.class);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AuditEventValidator validator;
    private final MeterRegistry registry;
    private final WalletHasher wallets;

    public JdbcAuditEventPublisher(JdbcClient jdbc, Clock clock, MeterRegistry registry, WalletHasher wallets) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.validator = new AuditEventValidator();
        this.registry = registry;
        this.wallets = wallets;
        for (Reason reason : Reason.values()) {
            rejected(reason);
        }
    }

    @Override
    public void publish(AuditEventCommand event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            refuse(event, new AuditPublishRejectedException("transaction", Reason.NO_TRANSACTION));
            throw new IllegalTransactionStateException(
                    "AuditEventPublisher.publish requires an active transaction (C9, propagation MANDATORY)");
        }
        try {
            validator.validate(event);
        } catch (AuditPublishRejectedException rejection) {
            refuse(event, rejection);
            throw rejection;
        }
        jdbc.sql("""
                        insert into audit_outbox (event_id, payload, created_at, delivered_at)
                        values (:id, cast(:payload as jsonb), :createdAt, null)
                        on conflict (event_id) do nothing
                        """)
                .param("id", event.eventId())
                .param("payload", AuditPayloadCodec.encode(event, walletHmac(event)))
                .param("createdAt", Timestamp.from(clock.instant()))
                .update();
        Counter published = Counter.builder("audit_events_published")
                .description("Audit events written to the outbox in committed transactions")
                .tag("action", event.action())
                .register(registry);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                published.increment();
            }
        });
    }

    /** u2-retention (BR2.1, BR2.2): the HMAC of the event's wallet; the full address itself is never written. */
    private Optional<String> walletHmac(AuditEventCommand event) {
        return Optional.ofNullable(event.wallet()).map(wallets::hmac);
    }

    private void refuse(AuditEventCommand event, AuditPublishRejectedException rejection) {
        rejected(rejection.reason()).increment();
        String action = AuditEventValidator.isValidAction(event.action()) ? event.action() : "<invalid>";
        log.error(
                "Audit event rejected by the publisher: action={}, reason={}, field={}",
                action,
                rejection.reason(),
                rejection.field());
    }

    private Counter rejected(Reason reason) {
        return Counter.builder("audit_publish_rejected")
                .description("Audit events refused by the publisher (defect of the calling module)")
                .tag("reason", reason.name())
                .register(registry);
    }
}
