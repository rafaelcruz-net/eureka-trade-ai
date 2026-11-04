package com.memetrade.ledger.application;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The audit events of U7 (C9) in the caller's transaction (NFR9.3). Details carry only closed values (status, kind,
 * reason) and identifiers, never amounts, prices, addresses or market data (NFR7.1). A Duplicate never publishes.
 */
@Component
public class LedgerAudit {

    private final AuditEventPublisher publisher;
    private final Clock clock;

    public LedgerAudit(AuditEventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    public void person(UUID tenantId, UUID userId, String action, String targetRef, Map<String, String> details) {
        publisher.publish(AuditEventCommand.person(
                tenantId, userId, action, targetRef, CorrelationId.current(), clock.instant(), details));
    }

    /** An action of a scheduled job (actor {@code system}); the bot is in the details, never the person. */
    public void system(String action, String targetRef, Map<String, String> details) {
        publisher.publish(
                AuditEventCommand.system(action, targetRef, CorrelationId.current(), clock.instant(), details));
    }

    /** BR9.1: an interface called for a bot of another person or tenant; one event per refusal. */
    public void tenantMismatch(UUID tenantId, UUID userId, UUID botId, String operation) {
        person(
                tenantId,
                userId,
                AuditActions.REQUEST_FORBIDDEN,
                "bot:" + botId,
                Map.of("reason", "TENANT_MISMATCH", "operation", operation, "role", "BETA_USER"));
    }
}
