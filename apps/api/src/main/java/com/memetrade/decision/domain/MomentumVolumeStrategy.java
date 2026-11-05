package com.memetrade.decision.domain;

import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MOMENTUM_VOLUME v1 (BR4.3): with {@code k = window / 5}, {@code b_0 = b_(n-k)}, BUY when
 * {@code round4((vol(b_n) - vol(b_0)) / vol(b_0) x 100) >= volumeGrowthMinPct} and
 * {@code round4((price(b_n) - price(b_0)) / price(b_0) x 100) >= priceRiseMinPct}; strength is the smaller of the two
 * ratios to their minimum. A zero base value is UNKNOWN_DATA, never a division by zero.
 */
final class MomentumVolumeStrategy {

    static final String WINDOW = "windowMinutes";
    static final String VOLUME_GROWTH = "volumeGrowthMinPct";
    static final String PRICE_RISE = "priceRiseMinPct";

    private MomentumVolumeStrategy() {}

    static TokenEvaluation evaluate(TokenInputs inputs, WindowReading window, DecisionRequest request) {
        TokenId token = inputs.token();
        int k = Numbers.buckets(request.parameter(WINDOW));
        BigDecimal minGrowth = request.parameter(VOLUME_GROWTH);
        BigDecimal minRise = request.parameter(PRICE_RISE);
        Optional<NoTradeReason> reason = window.check(k, true, request.now());
        if (reason.isEmpty()
                && (window.volume(k).signum() == 0 || window.price(k).signum() == 0)) {
            reason = Optional.of(NoTradeReason.UNKNOWN_DATA);
        }
        if (reason.isPresent()) {
            return TokenEvaluation.nothing(token, unknownFactors(token, minGrowth, minRise), reason);
        }
        BigDecimal growth = Numbers.changePct(window.volume(k), window.volume(0));
        BigDecimal rise = Numbers.changePct(window.price(k), window.price(0));
        boolean volumeOk = growth.compareTo(minGrowth) >= 0;
        boolean priceOk = rise.compareTo(minRise) >= 0;
        List<Factor> factors = new ArrayList<>();
        factors.add(Factor.measured(token, "volumeGrowthPct", FactorValue.of(growth), minGrowth, volumeOk));
        factors.add(Factor.measured(token, "priceRisePct", FactorValue.of(rise), minRise, priceOk));
        if (!(volumeOk && priceOk)) {
            return TokenEvaluation.nothing(token, factors, Optional.empty());
        }
        BigDecimal strength = Numbers.ratio(growth, minGrowth).min(Numbers.ratio(rise, minRise));
        factors.add(Factor.measured(
                token,
                "signalStrength",
                FactorValue.of(strength.setScale(4, RoundingMode.HALF_EVEN)),
                Numbers.ONE,
                true));
        return TokenEvaluation.signal(token, factors, strength);
    }

    private static List<Factor> unknownFactors(TokenId token, BigDecimal minGrowth, BigDecimal minRise) {
        return List.of(
                Factor.measured(token, "volumeGrowthPct", FactorValue.unknown(), minGrowth, false),
                Factor.measured(token, "priceRisePct", FactorValue.unknown(), minRise, false));
    }
}
