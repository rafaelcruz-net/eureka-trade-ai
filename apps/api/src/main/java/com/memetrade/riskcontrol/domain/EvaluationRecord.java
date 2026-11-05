package com.memetrade.riskcontrol.domain;

import com.memetrade.riskcontrol.api.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One {@code OrderEvaluation} (entities.md): the first evaluation of a decision, approved or rejected; written once and
 * never changed (BR3.4, BR4.3). Tenant and person come from the authentication context; the mode is always PAPER.
 */
public record EvaluationRecord(
        UUID evaluationId,
        UUID tenantId,
        UUID ownerUserId,
        UUID botId,
        UUID decisionId,
        OrderSide side,
        String chain,
        String tokenMint,
        BigDecimal requestedQuantity,
        long limitsVersion,
        Outcome outcome,
        Instant evaluatedAt) {

    public enum Outcome {
        APPROVED,
        REJECTED
    }

    public EvaluationRecord {
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(tokenMint, "tokenMint");
        Objects.requireNonNull(requestedQuantity, "requestedQuantity");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    }
}
