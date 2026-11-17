package com.memetrade.realexits.application;

import com.memetrade.audit.api.ActorType;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.audit.api.RealReferences;
import com.memetrade.realexits.domain.CancelReason;
import com.memetrade.realexits.domain.ConditionalOrder;
import com.memetrade.realexits.domain.ConditionalState;
import com.memetrade.realexits.infra.RealExitsMetrics;
import com.memetrade.shared.logging.LogMasking;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * BR5.5, U7-NFR5.11: every change of state of a conditional is audited in the transaction of the change (the outbox;
 * the mint and the signature only in the typed REAL fields), counted, and logged at INFO with the conditional, the
 * states and the code; signatures only masked, never an address, a key nor a transaction. The live channel reads the
 * change from the row itself ({@code updated_at}, written in the same transaction), so a change rolled back is never
 * announced.
 */
@Component
public class ExitEvents {

    private static final Logger log = LoggerFactory.getLogger(ExitEvents.class);

    private final AuditEventPublisher publisher;
    private final RealExitsMetrics metrics;
    private final Clock clock;

    public ExitEvents(AuditEventPublisher publisher, RealExitsMetrics metrics, Clock clock) {
        this.publisher = publisher;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** A cancellation of a job or of the guard moved the conditional to CANCELLING or CANCELLED. */
    public void cancelled(ConditionalOrder conditional, ConditionalState to, CancelReason reason) {
        cancelled(conditional, to, reason, Optional.empty(), Optional.empty());
    }

    /** A cancellation moved the conditional to CANCELLING or CANCELLED (counted once it is CANCELLED). */
    public void cancelled(
            ConditionalOrder conditional,
            ConditionalState to,
            CancelReason reason,
            Optional<UUID> person,
            Optional<String> signature) {
        changed(conditional, to, reason.name(), person, signature);
        if (to == ConditionalState.CANCELLED) {
            metrics.cancelled(reason);
        }
    }

    /**
     * @param person the person who caused the change (her route), or empty for the jobs (actor system)
     * @param signature the transaction of the change on the network, when there is one
     */
    public void changed(
            ConditionalOrder conditional,
            ConditionalState to,
            String code,
            Optional<UUID> person,
            Optional<String> signature) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("from", conditional.state().name());
        details.put("to", to.name());
        details.put("code", code == null ? "NONE" : code);
        details.put("kind", conditional.kind().name());
        details.put("adapter", conditional.adapter().name());
        String target = "real-conditional:" + conditional.conditionalOrderId();
        AuditEventCommand command = person.isPresent()
                ? AuditEventCommand.person(
                        conditional.tenantId(),
                        person.get(),
                        "REAL_CONDITIONAL_STATE_CHANGED",
                        target,
                        CorrelationId.current(),
                        clock.instant(),
                        details)
                : new AuditEventCommand(
                        UUID.randomUUID(),
                        Optional.of(conditional.tenantId()),
                        AuditEventCommand.SYSTEM_ACTOR,
                        ActorType.SYSTEM,
                        "REAL_CONDITIONAL_STATE_CHANGED",
                        target,
                        CorrelationId.current(),
                        clock.instant(),
                        details);
        publisher.publish(
                command.withReal(new RealReferences(signature.orElse(null), conditional.positionMint(), null)));
        log.info(
                "REAL conditional state: conditional={}, from={}, to={}, code={}, signature={}",
                conditional.conditionalOrderId(),
                conditional.state(),
                to,
                code == null ? "NONE" : code,
                signature.map(LogMasking::maskBase58).orElse("-"));
    }
}
