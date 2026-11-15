package com.memetrade.riskcontrol.real.infra;

import com.memetrade.audit.api.ActorType;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.audit.api.RealReferences;
import com.memetrade.riskcontrol.api.BlockedToken;
import com.memetrade.riskcontrol.api.OrderIntent;
import com.memetrade.riskcontrol.api.OrderOrigin;
import com.memetrade.riskcontrol.real.domain.RealVerdict;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 261113-trading-real-bot U3 (BR7.1, BR2.3, U3-NFR6.2, M8): the audit events of the REAL barrier, through the existing
 * outbox in the transaction of the caller ({@code MANDATORY}, like the PAPER barrier). The mint goes only in its typed
 * field; the free details carry the ids, the codes and the integer figures of the wallet, never a mint nor an address.
 *
 * <ul>
 *   <li>{@code REAL_ORDER_REJECTED}: actor {@code system} in the tenant of the bot for a decision of the bot (target
 *       {@code bot:<botId>}), or the person for the liquidation (target {@code user:<userId>});
 *   <li>{@code REAL_TOKEN_BLOCKED} and {@code REAL_TOKEN_UNBLOCKED}: actor the administrator, no tenant (the list is
 *       global), with the reason (the previous one for an unblock).
 * </ul>
 */
@Component
public class RealRiskAudit {

    static final String ORDER_REJECTED = "REAL_ORDER_REJECTED";
    static final String TOKEN_BLOCKED = "REAL_TOKEN_BLOCKED";
    static final String TOKEN_UNBLOCKED = "REAL_TOKEN_UNBLOCKED";

    private final AuditEventPublisher publisher;
    private final Clock clock;

    public RealRiskAudit(AuditEventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    public void rejected(OrderIntent intent, UUID evaluationId, RealVerdict verdict) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("evaluationId", evaluationId.toString());
        if (intent.decisionId() != null) {
            details.put("decisionId", intent.decisionId().toString());
        }
        details.put("origin", intent.origin().name());
        details.put("side", intent.side().name());
        details.put("code", verdict.code().name());
        details.put("detail", verdict.detail().name());
        details.put(
                "figures",
                verdict.figures().entrySet().stream()
                        .map(figure -> figure.getKey() + "=" + figure.getValue())
                        .collect(Collectors.joining(",")));
        boolean bot = intent.origin() == OrderOrigin.BOT;
        AuditEventCommand event = new AuditEventCommand(
                UUID.randomUUID(),
                Optional.of(intent.tenantId()),
                bot ? AuditEventCommand.SYSTEM_ACTOR : intent.userId().toString(),
                bot ? ActorType.SYSTEM : ActorType.PERSON,
                ORDER_REJECTED,
                bot ? "bot:" + intent.botId() : "user:" + intent.userId(),
                CorrelationId.current(),
                clock.instant(),
                details,
                null);
        publisher.publish(event.withReal(new RealReferences(null, intent.mint(), null)));
    }

    public void blocked(BlockedToken token) {
        admin(TOKEN_BLOCKED, token.blockedBy(), token, token.reason());
    }

    public void unblocked(BlockedToken previous, UUID adminId) {
        admin(TOKEN_UNBLOCKED, adminId, previous, previous.reason());
    }

    private void admin(String action, UUID adminId, BlockedToken token, String reason) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("chain", token.chain());
        details.put("reason", reason);
        publisher.publish(AuditEventCommand.admin(
                        adminId,
                        action,
                        "real-block-list:" + token.chain(),
                        CorrelationId.current(),
                        clock.instant(),
                        details)
                .withReal(new RealReferences(null, token.mint(), null)));
    }
}
