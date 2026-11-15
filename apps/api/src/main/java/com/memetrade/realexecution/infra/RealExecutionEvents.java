package com.memetrade.realexecution.infra;

import com.memetrade.audit.api.ActorType;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.audit.api.RealReferences;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.infra.JdbcRealExecutionStore.OrderRow;
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
 * BR10.1, observability-design.md: every change of state of a REAL order is audited in the transaction of the change
 * (outbox C9, the mint and the signature only in the typed fields of M8), counted, and logged at INFO with the order,
 * the states and the code; the signature only masked, never the transaction. The live channel reads the change from
 * the order itself ({@code updated_at}, written in the same transaction), so a change rolled back is never announced.
 */
@Component
public class RealExecutionEvents {

    private static final Logger log = LoggerFactory.getLogger(RealExecutionEvents.class);

    private final AuditEventPublisher publisher;
    private final RealExecutionMetrics metrics;
    private final Clock clock;

    public RealExecutionEvents(AuditEventPublisher publisher, RealExecutionMetrics metrics, Clock clock) {
        this.publisher = publisher;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * @param person the person who caused the change (route of the person), or empty for the worker (actor system)
     */
    public void transition(OrderRow order, RealOrderState from, RealOrderState to, String code, Optional<UUID> person) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("from", from == null ? "NONE" : from.name());
        details.put("to", to.name());
        details.put("code", code == null ? "NONE" : code);
        details.put("side", order.side().name());
        details.put("origin", order.origin());
        details.put("mode", order.operationMode());
        RealReferences references = new RealReferences(order.txSignature(), order.mint(), null);
        String target = "real-order:" + order.realOrderId();
        AuditEventCommand command = person.isPresent()
                ? AuditEventCommand.person(
                        order.tenantId(),
                        person.get(),
                        "REAL_ORDER_STATE_CHANGED",
                        target,
                        CorrelationId.current(),
                        clock.instant(),
                        details)
                : new AuditEventCommand(
                        UUID.randomUUID(),
                        Optional.of(order.tenantId()),
                        AuditEventCommand.SYSTEM_ACTOR,
                        ActorType.SYSTEM,
                        "REAL_ORDER_STATE_CHANGED",
                        target,
                        CorrelationId.current(),
                        clock.instant(),
                        details,
                        null);
        publisher.publish(command.withReal(references));
        metrics.transition(order.side(), order.operationMode(), to, code);
        log.info(
                "REAL order state: order={}, from={}, to={}, code={}, side={}, signature={}",
                order.realOrderId(),
                from,
                to,
                code == null ? "NONE" : code,
                order.side(),
                order.txSignature() == null ? "-" : LogMasking.maskBase58(order.txSignature()));
    }
}
