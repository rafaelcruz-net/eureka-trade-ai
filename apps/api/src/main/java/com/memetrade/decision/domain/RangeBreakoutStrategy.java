package com.memetrade.decision.domain;

import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * RANGE_BREAKOUT v1 (BR4.4): with {@code m = window / 5}, the range is the maximum and minimum price of
 * {@code b_(n-m)} to {@code b_(n-1)}; BUY when {@code price(b_n) >= max x (1 + margin / 100)}, with strength
 * {@code price(b_n) / threshold}. The minimum of the range also gives the exit RANGE_EXIT (BR4.5).
 */
final class RangeBreakoutStrategy {

    static final String WINDOW = "windowMinutes";
    static final String MARGIN = "breakoutMarginPct";

    private RangeBreakoutStrategy() {}

    static TokenEvaluation evaluate(TokenInputs inputs, WindowReading window, DecisionRequest request) {
        TokenId token = inputs.token();
        int m = Numbers.buckets(request.parameter(WINDOW));
        Optional<NoTradeReason> reason = window.check(m, false, request.now());
        if (reason.isPresent()) {
            return TokenEvaluation.nothing(
                    token,
                    List.of(new Factor(
                            token,
                            "breakoutThreshold",
                            FactorValue.unknown(),
                            Optional.empty(),
                            false,
                            Optional.empty())),
                    reason);
        }
        BigDecimal threshold = threshold(window, m, request.parameter(MARGIN));
        BigDecimal last = window.price(0);
        boolean broke = last.compareTo(threshold) >= 0;
        List<Factor> factors = new ArrayList<>();
        factors.add(Factor.measured(token, "breakoutThreshold", FactorValue.of(threshold), threshold, broke));
        factors.add(Factor.measured(token, "lastPrice", FactorValue.of(last), threshold, broke));
        if (!broke) {
            return TokenEvaluation.nothing(token, factors, Optional.empty());
        }
        BigDecimal strength = Numbers.ratio(last, threshold);
        factors.add(Factor.measured(
                token,
                "signalStrength",
                FactorValue.of(strength.setScale(4, RoundingMode.HALF_EVEN)),
                Numbers.ONE,
                true));
        return TokenEvaluation.signal(token, factors, strength);
    }

    /** {@code max(b_(n-m) .. b_(n-1)) x (1 + margin / 100)}, unit price with 12 places. */
    static BigDecimal threshold(WindowReading window, int m, BigDecimal marginPct) {
        BigDecimal max = window.price(m);
        for (int back = m - 1; back >= 1; back--) {
            max = max.max(window.price(back));
        }
        BigDecimal factor = Numbers.HUNDRED.add(marginPct).divide(Numbers.HUNDRED);
        return max.multiply(factor).setScale(Numbers.PRICE_SCALE, RoundingMode.HALF_EVEN);
    }

    /** {@code min(b_(n-m) .. b_(n-1))}. */
    static BigDecimal rangeMin(WindowReading window, int m) {
        BigDecimal min = window.price(m);
        for (int back = m - 1; back >= 1; back--) {
            min = min.min(window.price(back));
        }
        return min;
    }
}
