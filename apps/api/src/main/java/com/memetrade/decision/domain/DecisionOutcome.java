package com.memetrade.decision.domain;

import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of the decision engine (C4 {@code Decision}, BR4.10). {@code token} is empty only for
 * {@link NoTradeReason#NO_TOKENS}; NO_TRADE has a reason, SELL an exit reason, and BUY and SELL a quantity above zero.
 */
public record DecisionOutcome(
        DecisionAction action,
        Optional<TokenId> token,
        Optional<NoTradeReason> noTradeReason,
        Optional<ExitReason> exitReason,
        Optional<BigDecimal> requestedQuantity,
        List<Factor> factors,
        String summary,
        Instant validUntil) {

    public DecisionOutcome {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(noTradeReason, "noTradeReason");
        Objects.requireNonNull(exitReason, "exitReason");
        Objects.requireNonNull(requestedQuantity, "requestedQuantity");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(validUntil, "validUntil");
        factors = List.copyOf(factors);
        if (action == DecisionAction.NO_TRADE && noTradeReason.isEmpty()) {
            throw new IllegalArgumentException("NO_TRADE requires a reason");
        }
        if (action == DecisionAction.SELL && exitReason.isEmpty()) {
            throw new IllegalArgumentException("SELL requires an exit reason");
        }
        boolean order = action == DecisionAction.BUY || action == DecisionAction.SELL;
        if (order
                && (token.isEmpty()
                        || requestedQuantity.map(q -> q.signum() <= 0).orElse(true))) {
            throw new IllegalArgumentException("BUY and SELL require a token and a quantity above zero");
        }
        if (summary.length() > 300) {
            throw new IllegalArgumentException("The summary has at most 300 characters");
        }
    }

    public boolean isOrder() {
        return action == DecisionAction.BUY || action == DecisionAction.SELL;
    }
}
