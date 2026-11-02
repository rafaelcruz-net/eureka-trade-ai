package com.memetrade.riskassessment.domain;

import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.riskassessment.api.DecisionHint;
import com.memetrade.riskassessment.api.FactorDefinition;
import com.memetrade.riskassessment.api.FactorId;
import com.memetrade.riskassessment.api.RiskBand;
import com.memetrade.riskassessment.api.UnavailableReason;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * F5 for one token (BR3.1 to BR3.6, BR3.8): pure, deterministic, exact decimals only (never floating point).
 *
 * <ol>
 *   <li>each factor: the curve risk of a KNOWN input (stale inputs count, BR3.6); an UNKNOWN input is risk 100 and its
 *       contribution UNKNOWN (BR3.4);
 *   <li>contribution {@code weight / 100 x risk} at scale 4 half up; coverage = sum of the weights of KNOWN inputs;
 *   <li>every source of the token FAILED: UNAVAILABLE {@code SOURCE_UNAVAILABLE}, every input
 *       {@code Unknown(SOURCE_UNAVAILABLE)}, coverage 0 (BR3.8);
 *   <li>coverage below the minimum: UNAVAILABLE {@code INSUFFICIENT_COVERAGE} (BR3.3);
 *   <li>otherwise the indicator is the sum of the contributions rounded half up, 0 to 100; the band comes from the
 *       version (BR3.5) and an UNKNOWN factor never leaves it LOW: it is raised to MEDIUM with
 *       {@code bandRaisedByUnknown} (BR3.4).
 * </ol>
 */
public final class RiskCalculator {

    static final int SCALE = 4;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int UNKNOWN_RISK = 100;

    private RiskCalculator() {}

    /**
     * @param inputs the input of each factor of the version; a missing entry counts as {@code NOT_YET_OBSERVED}
     * @param allSourcesFailed every source that informs the token is FAILED
     */
    public static RiskComputation calculate(
            WeightSet weights, Map<FactorId, MarketValue> inputs, boolean allSourcesFailed) {
        List<FactorResult> factors = new ArrayList<>();
        BigDecimal coverage = BigDecimal.ZERO.setScale(SCALE);
        BigDecimal total = BigDecimal.ZERO;
        boolean anyUnknown = false;
        for (FactorDefinition definition : weights.factors()) {
            MarketValue input = allSourcesFailed
                    ? new MarketValue.Unknown(UnknownReason.SOURCE_UNAVAILABLE, Optional.empty())
                    : inputs.getOrDefault(
                            definition.factorId(),
                            new MarketValue.Unknown(UnknownReason.NOT_YET_OBSERVED, Optional.empty()));
            FactorResult factor = factor(definition, input);
            factors.add(factor);
            total = total.add(contributionOf(definition.weight(), factor.factorRisk()));
            if (factor.isUnknown()) {
                anyUnknown = true;
            } else {
                coverage = coverage.add(definition.weight());
            }
        }
        coverage = coverage.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal minCoverage = weights.minCoverage().setScale(SCALE, RoundingMode.HALF_UP);
        if (allSourcesFailed) {
            return unavailable(weights, UnavailableReason.SOURCE_UNAVAILABLE, coverage, minCoverage, factors);
        }
        if (coverage.compareTo(minCoverage) < 0) {
            return unavailable(weights, UnavailableReason.INSUFFICIENT_COVERAGE, coverage, minCoverage, factors);
        }
        int score = Math.max(
                0, Math.min(100, total.setScale(0, RoundingMode.HALF_UP).intValueExact()));
        RiskBand band = band(score, weights);
        boolean raised = anyUnknown && band == RiskBand.LOW;
        return new RiskComputation(
                weights.version(),
                true,
                Optional.of(score),
                Optional.of(raised ? RiskBand.MEDIUM : band),
                raised,
                Optional.empty(),
                coverage,
                minCoverage,
                DecisionHint.ELIGIBLE,
                factors);
    }

    /** BR3.5: score at or below lowMax is LOW, at or below mediumMax MEDIUM, above it HIGH. */
    public static RiskBand band(int score, WeightSet weights) {
        if (score <= weights.lowMax()) {
            return RiskBand.LOW;
        }
        return score <= weights.mediumMax() ? RiskBand.MEDIUM : RiskBand.HIGH;
    }

    static BigDecimal contributionOf(BigDecimal weight, int risk) {
        return weight.multiply(BigDecimal.valueOf(risk)).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
    }

    private static FactorResult factor(FactorDefinition definition, MarketValue input) {
        if (input instanceof MarketValue.Known known) {
            int risk = Curve.risk(definition.curve(), known.value());
            return new FactorResult(
                    definition.factorId(),
                    definition.weight(),
                    input,
                    risk,
                    Optional.of(contributionOf(definition.weight(), risk)));
        }
        return new FactorResult(definition.factorId(), definition.weight(), input, UNKNOWN_RISK, Optional.empty());
    }

    private static RiskComputation unavailable(
            WeightSet weights,
            UnavailableReason reason,
            BigDecimal coverage,
            BigDecimal minCoverage,
            List<FactorResult> factors) {
        return new RiskComputation(
                weights.version(),
                false,
                Optional.empty(),
                Optional.empty(),
                false,
                Optional.of(reason),
                coverage,
                minCoverage,
                DecisionHint.NO_TRADE,
                factors);
    }
}
