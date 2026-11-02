package com.memetrade.riskassessment.domain;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketRiskInputs.TokenInputs;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.riskassessment.api.FactorId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * F5 steps 2.1 to 2.7 for one token: turns the market inputs into factor inputs (LIQUIDITY, HOLDERS and VOLUME_24H by
 * the displayed value, PRICE_CHANGE_1H by BR1.10), calculates, and summarises what the result depends on.
 */
public final class RiskEvaluator {

    /** A computation and its {@code inputsFingerprint}. */
    public record Evaluation(RiskComputation computation, String fingerprint) {}

    private RiskEvaluator() {}

    public static Evaluation evaluate(WeightSet weights, TokenInputs inputs) {
        Map<FactorId, MarketValue> factors = new EnumMap<>(FactorId.class);
        MarketValue price = inputs.value(MarketField.PRICE);
        factors.put(FactorId.LIQUIDITY, inputs.value(MarketField.LIQUIDITY));
        factors.put(FactorId.HOLDERS, inputs.value(MarketField.HOLDERS));
        factors.put(FactorId.VOLUME_24H, inputs.value(MarketField.VOLUME_24H));
        factors.put(FactorId.PRICE_CHANGE_1H, PriceChange.of(price, inputs.priceOneHourAgo()));
        RiskComputation computation = RiskCalculator.calculate(weights, factors, inputs.allSourcesFailed());
        Map<FactorId, MarketValue> used = new EnumMap<>(FactorId.class);
        computation.factors().forEach(factor -> used.put(factor.factorId(), factor.input()));
        String fingerprint = InputsFingerprint.of(weights.version(), used, List.of(price, inputs.priceOneHourAgo()));
        return new Evaluation(computation, fingerprint);
    }

    /** NFR8.1: recalculates from the factor inputs stored with an assessment; the same version gives the same result. */
    public static RiskComputation recalculate(WeightSet weights, List<FactorResult> stored, boolean allSourcesFailed) {
        Map<FactorId, MarketValue> inputs = new EnumMap<>(FactorId.class);
        stored.forEach(factor -> inputs.put(factor.factorId(), factor.input()));
        return RiskCalculator.calculate(weights, inputs, allSourcesFailed);
    }
}
