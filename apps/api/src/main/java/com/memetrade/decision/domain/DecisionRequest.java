package com.memetrade.decision.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Every input of one decision (BR4.2): the decision is a pure function of this record, so recomputing it from the
 * recorded values gives the same decision.
 *
 * @param parameters the strategy parameters by key (BR4.3 to BR4.6), exact decimals
 * @param maxOrderSize USDC, the cap of a buy of the limits version in force (BR4.6)
 * @param availableBalance USDC, the available virtual balance read from U7 (BR4.6)
 * @param tokens the evaluated tokens in list order: explicit list, filtered tokens, tokens with a position (BR4.8)
 * @param positions the open positions of the bot
 * @param slotStart the start of the 5-minute UTC slot; the last closed bucket starts 5 minutes before it
 * @param now the instant of the decision (injectable clock)
 */
public record DecisionRequest(
        StrategyId strategy,
        String strategyVersion,
        Map<String, BigDecimal> parameters,
        long limitsVersion,
        BigDecimal maxOrderSize,
        BigDecimal availableBalance,
        List<TokenInputs> tokens,
        List<PositionInput> positions,
        Instant slotStart,
        Instant now) {

    public DecisionRequest {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(strategyVersion, "strategyVersion");
        Objects.requireNonNull(maxOrderSize, "maxOrderSize");
        Objects.requireNonNull(availableBalance, "availableBalance");
        Objects.requireNonNull(slotStart, "slotStart");
        Objects.requireNonNull(now, "now");
        parameters = Map.copyOf(parameters);
        tokens = List.copyOf(tokens);
        positions = List.copyOf(positions);
    }

    /** A parameter of the strategy; a missing key is a programming error (the bot was validated, BR1.2). */
    public BigDecimal parameter(String key) {
        BigDecimal value = parameters.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing strategy parameter " + key);
        }
        return value;
    }
}
