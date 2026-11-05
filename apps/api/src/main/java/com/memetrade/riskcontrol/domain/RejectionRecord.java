package com.memetrade.riskcontrol.domain;

import com.memetrade.riskcontrol.api.OrderSide;
import com.memetrade.riskcontrol.api.PauseTrigger;
import com.memetrade.riskcontrol.api.RejectionReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One {@code OrderRejection} with its {@code RejectionReasonDetail}s (entities.md, BR3.5): the reasons in the fixed
 * order (one detail each), the pause triggers, the limits version and the MarketInputs of the call; written once and
 * never changed. {@code evaluationId} is the evaluation of this decision, or the original one for a DUPLICATE.
 */
public record RejectionRecord(
        UUID rejectionId,
        UUID tenantId,
        UUID ownerUserId,
        UUID botId,
        UUID decisionId,
        UUID evaluationId,
        OrderSide side,
        String chain,
        String tokenMint,
        BigDecimal requestedQuantity,
        List<ReasonDetail> details,
        Set<PauseTrigger> pauseTriggers,
        long limitsVersion,
        MarketInputs market,
        Instant evaluatedAt) {

    public RejectionRecord {
        Objects.requireNonNull(rejectionId, "rejectionId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        if (details == null || details.isEmpty()) {
            throw new IllegalArgumentException("A rejection has at least one reason with its detail");
        }
        details = List.copyOf(details);
        pauseTriggers = pauseTriggers.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(pauseTriggers));
    }

    public List<RejectionReason> reasons() {
        return details.stream().map(ReasonDetail::reason).toList();
    }
}
