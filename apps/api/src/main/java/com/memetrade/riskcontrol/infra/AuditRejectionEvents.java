package com.memetrade.riskcontrol.infra;

import com.memetrade.audit.api.ActorType;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.riskcontrol.domain.RejectionEvents;
import com.memetrade.riskcontrol.domain.RejectionRecord;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * {@code ORDER_REJECTED} through the publisher of C9, in the cycle transaction (BR3.5, NFR9.4): actor {@code system}
 * (the barrier acts inside the bot cycle, not on a request of the person), the tenant of the context, target
 * {@code bot:<botId>} and details {rejectionId, decisionId, reasons, pauseTriggers, limitsVersion}. Never a wallet, a
 * secret or a market value (NFR7.1).
 */
@Component
public class AuditRejectionEvents implements RejectionEvents {

    private final AuditEventPublisher publisher;
    private final Clock clock;

    public AuditRejectionEvents(AuditEventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    @Override
    public void rejected(RejectionRecord rejection) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("rejectionId", rejection.rejectionId().toString());
        details.put("decisionId", rejection.decisionId().toString());
        details.put("reasons", rejection.reasons().stream().map(Enum::name).collect(Collectors.joining(",")));
        details.put(
                "pauseTriggers",
                rejection.pauseTriggers().stream().map(Enum::name).collect(Collectors.joining(",")));
        details.put("limitsVersion", Long.toString(rejection.limitsVersion()));
        publisher.publish(new AuditEventCommand(
                UUID.randomUUID(),
                Optional.of(rejection.tenantId()),
                AuditEventCommand.SYSTEM_ACTOR,
                ActorType.SYSTEM,
                AuditActions.ORDER_REJECTED,
                "bot:" + rejection.botId(),
                CorrelationId.current(),
                clock.instant(),
                details));
    }
}
