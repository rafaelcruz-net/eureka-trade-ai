package com.memetrade.pnl.application;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.identity.api.Principal;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Audit events of the {@code pnl} module (C9); identifiers and closed values only. */
@Component
public class PnlAudit {

    private final AuditEventPublisher publisher;
    private final Clock clock;
    private final TransactionTemplate ownTransaction;

    public PnlAudit(AuditEventPublisher publisher, Clock clock, PlatformTransactionManager transactions) {
        this.publisher = publisher;
        this.clock = clock;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * BR9.1: C13 called for a bot of another person or tenant. Like every 403 of U1, the single REQUEST_FORBIDDEN is
     * written in its OWN transaction, so the refusal (an exception of C13) never loses its event.
     */
    public void tenantMismatch(Principal person, UUID botId, String operation) {
        ownTransaction.executeWithoutResult(status -> publisher.publish(AuditEventCommand.person(
                person.tenantId(),
                person.userId(),
                AuditActions.REQUEST_FORBIDDEN,
                "bot:" + botId,
                CorrelationId.current(),
                clock.instant(),
                Map.of(
                        "reason",
                        "TENANT_MISMATCH",
                        "operation",
                        operation,
                        "role",
                        person.role().name()))));
    }
}
