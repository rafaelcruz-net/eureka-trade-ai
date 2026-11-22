package com.memetrade.bots.infra;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.bots.domain.RealIneligibilityReason;
import com.memetrade.identity.api.Principal;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 261121-paper-real-unico U1 (BR2.4, NFR3.5; security-design.md "Auditoria da recusa REAL"): the audit event of a REAL
 * creation refused with {@code 409 REAL_NOT_ELIGIBLE}, written in its OWN transaction ({@code REQUIRES_NEW}) after the
 * command transaction rolled back, so the refusal never loses its record and the write never holds two connections.
 * Actor: the person; target {@code bot-creation} (no bot exists); details: the reason and {@code market = REAL}. No
 * address, amount nor key.
 *
 * <p>Not bound to a process role: the {@code BotCommandService} that calls it exists in the api and in the worker
 * (observability-design.md "Processos").
 */
@Component
public class RealCreationRefusalAudit {

    public static final String TARGET = "bot-creation";

    private final AuditEventPublisher publisher;
    private final Clock clock;
    private final TransactionTemplate ownTransaction;

    public RealCreationRefusalAudit(
            AuditEventPublisher publisher, Clock clock, PlatformTransactionManager transactions) {
        this.publisher = publisher;
        this.clock = clock;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void refused(Principal person, RealIneligibilityReason reason) {
        ownTransaction.executeWithoutResult(status -> publisher.publish(AuditEventCommand.person(
                person.tenantId(),
                person.userId(),
                AuditActions.BOT_REAL_CREATION_REFUSED,
                TARGET,
                CorrelationId.current(),
                clock.instant(),
                Map.of("reason", reason.name(), "market", "REAL"))));
    }
}
