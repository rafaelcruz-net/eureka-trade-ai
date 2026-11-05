package com.memetrade.decision.domain;

import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * BR4.5: the open position of a token is sold WHOLE at the target ({@code TAKE_PROFIT}), the stop ({@code STOP_LOSS}),
 * the maximum time ({@code MAX_HOLD}) or, in RANGE_BREAKOUT, when the last bucket price goes below the minimum of the
 * range ({@code RANGE_EXIT}), in this order. Without a fresh reference price no exit is evaluated (STALE_DATA, the
 * position stays); an UNKNOWN or short window only removes RANGE_EXIT.
 */
final class ExitRules {

    static final String TAKE_PROFIT = "takeProfitPct";
    static final String STOP_LOSS = "stopLossPct";
    static final String MAX_HOLD = "maxHoldMinutes";

    private ExitRules() {}

    static TokenEvaluation evaluate(
            TokenInputs inputs, PositionInput position, WindowReading window, DecisionRequest request) {
        TokenId token = inputs.token();
        BigDecimal takeProfit = request.parameter(TAKE_PROFIT);
        BigDecimal stopLoss = request.parameter(STOP_LOSS);
        BigDecimal maxHold = request.parameter(MAX_HOLD);
        BigDecimal heldMinutes = BigDecimal.valueOf(
                Duration.between(position.openedAt(), request.now()).toMinutes());
        boolean timeUp = heldMinutes.compareTo(maxHold) >= 0;
        List<Factor> factors = new ArrayList<>();
        if (!WindowReading.fresh(inputs.referencePrice(), request.now())) {
            factors.add(Factor.measured(token, "pnlPct", FactorValue.unknown(), takeProfit, false));
            factors.add(Factor.measured(token, "holdMinutes", FactorValue.of(heldMinutes), maxHold, false));
            return TokenEvaluation.nothing(token, factors, Optional.of(NoTradeReason.STALE_DATA));
        }
        BigDecimal reference = ((MarketValue.Known) inputs.referencePrice()).value();
        BigDecimal average =
                position.costBasis().divide(position.quantity(), Numbers.PRICE_SCALE, RoundingMode.HALF_EVEN);
        BigDecimal pnl = Numbers.changePct(average, reference);
        boolean target = pnl.compareTo(takeProfit) >= 0;
        boolean stop = pnl.compareTo(stopLoss.negate()) <= 0;
        factors.add(Factor.measured(token, "pnlPct", FactorValue.of(pnl), takeProfit, target));
        factors.add(Factor.measured(token, "stopLossPct", FactorValue.of(pnl), stopLoss.negate(), stop));
        factors.add(Factor.measured(token, "holdMinutes", FactorValue.of(heldMinutes), maxHold, timeUp));
        boolean rangeExit = rangeExit(request, window, token, factors);
        if (target) {
            return TokenEvaluation.exit(token, factors, ExitReason.TAKE_PROFIT);
        }
        if (stop) {
            return TokenEvaluation.exit(token, factors, ExitReason.STOP_LOSS);
        }
        if (timeUp) {
            return TokenEvaluation.exit(token, factors, ExitReason.MAX_HOLD);
        }
        if (rangeExit) {
            return TokenEvaluation.exit(token, factors, ExitReason.RANGE_EXIT);
        }
        return TokenEvaluation.nothing(token, factors, Optional.empty());
    }

    /** RANGE_EXIT only for RANGE_BREAKOUT with a complete, known and fresh window. */
    private static boolean rangeExit(
            DecisionRequest request, WindowReading window, TokenId token, List<Factor> factors) {
        if (request.strategy() != StrategyId.RANGE_BREAKOUT) {
            return false;
        }
        int m = Numbers.buckets(request.parameter(RangeBreakoutStrategy.WINDOW));
        if (window.check(m, false, request.now()).isPresent()) {
            factors.add(
                    new Factor(token, "rangeMin", FactorValue.unknown(), Optional.empty(), false, Optional.empty()));
            return false;
        }
        BigDecimal min = RangeBreakoutStrategy.rangeMin(window, m);
        boolean below = window.price(0).compareTo(min) < 0;
        factors.add(Factor.measured(token, "rangeMin", FactorValue.of(window.price(0)), min, below));
        return below;
    }
}
