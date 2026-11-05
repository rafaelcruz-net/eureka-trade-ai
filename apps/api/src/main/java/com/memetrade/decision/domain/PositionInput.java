package com.memetrade.decision.domain;

import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * An open position of the bot as the decision sees it (BR4.5, BR3.7): quantity and cost basis read from U7, and the
 * opening instant kept by U5 ({@code BotHolding}) or, without one, the {@code updatedAt} of the U7 position.
 */
public record PositionInput(TokenId token, BigDecimal quantity, BigDecimal costBasis, Instant openedAt) {

    public PositionInput {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(costBasis, "costBasis");
        Objects.requireNonNull(openedAt, "openedAt");
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("An open position has a quantity above zero");
        }
    }
}
