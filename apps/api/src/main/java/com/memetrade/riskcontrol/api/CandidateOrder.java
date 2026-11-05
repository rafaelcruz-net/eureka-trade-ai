package com.memetrade.riskcontrol.api;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * The candidate order U5 submits to the barrier (C14, entities.md). It carries no tenant and no person: they come from
 * the authentication context (BR1.1). A missing field or a quantity not above zero is a programming error of U5
 * (BR1.2) and throws.
 *
 * @param requestedQuantity token quantity, greater than zero; evaluated as received (U7 truncates on execution)
 */
public record CandidateOrder(
        UUID botId, UUID decisionId, OrderSide side, String chain, String tokenMint, BigDecimal requestedQuantity) {

    public CandidateOrder {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(side, "side");
        requireText(chain, "chain");
        requireText(tokenMint, "tokenMint");
        Objects.requireNonNull(requestedQuantity, "requestedQuantity");
        if (requestedQuantity.signum() <= 0) {
            throw new IllegalArgumentException("requestedQuantity must be greater than zero (BR1.2)");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required (BR1.2)");
        }
    }
}
