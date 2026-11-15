package com.memetrade.botwallet.infra;

import com.memetrade.audit.api.ActorType;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.audit.api.RealReferences;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Audit events of the REAL steps of the U1 (BR6.1, BR6.2): written to the outbox in the caller's transaction. The bot
 * wallet and the transaction signature go only in the typed fields; the main wallet only as {@code wallet} (stored as
 * its HMAC); the free details carry amounts, states and codes.
 */
@Component
public class RealAudit {

    private final AuditEventPublisher publisher;
    private final Clock clock;

    public RealAudit(AuditEventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    /** An event of a person inside their tenant. */
    public void person(
            UUID tenantId,
            UUID userId,
            String action,
            String targetRef,
            Map<String, String> details,
            RealReferences real,
            String mainWallet) {
        AuditEventCommand command = AuditEventCommand.person(
                        tenantId, userId, action, targetRef, CorrelationId.current(), clock.instant(), details)
                .withReal(real);
        publisher.publish(mainWallet == null ? command : command.withWallet(mainWallet));
    }

    /** An event of the worker about a wallet of the tenant. */
    public void system(
            UUID tenantId, String action, String targetRef, Map<String, String> details, RealReferences real) {
        publisher.publish(new AuditEventCommand(
                        UUID.randomUUID(),
                        Optional.of(tenantId),
                        AuditEventCommand.SYSTEM_ACTOR,
                        ActorType.SYSTEM,
                        action,
                        targetRef,
                        CorrelationId.current(),
                        clock.instant(),
                        details,
                        null)
                .withReal(real));
    }
}
