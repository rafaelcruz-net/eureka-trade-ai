package com.memetrade.riskassessment.domain;

import com.memetrade.riskassessment.api.CurvePoint;
import com.memetrade.riskassessment.api.FactorDefinition;
import com.memetrade.riskassessment.api.FactorId;
import com.memetrade.riskassessment.api.WeightSetDraft;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * BR4.1: a weight version is accepted only when complete and coherent. Weights in percentage points with scale up to
 * 4, each above 0, adding up to exactly 100.0000; exactly the four factors of the beta catalogue, once each; each
 * curve with 2 to 8 points, inputs not negative and strictly increasing, risks from 0 to 100;
 * {@code 0 <= lowMax < mediumMax < 100}; {@code 0 < minCoverage <= 100.0000} with scale up to 4. Every violation is
 * reported (never only the first), by field.
 */
public final class WeightSetValidator {

    static final BigDecimal HUNDRED = new BigDecimal("100");
    static final int MAX_SCALE = 4;
    static final int MIN_POINTS = 2;
    static final int MAX_POINTS = 8;

    /** One invalid field of the draft. */
    public record Violation(String field, String message) {}

    private WeightSetValidator() {}

    public static List<Violation> validate(WeightSetDraft draft) {
        List<Violation> violations = new ArrayList<>();
        if (draft == null) {
            return List.of(new Violation("body", "A weight version is required."));
        }
        validateFactors(draft.factors(), violations);
        if (draft.lowMax() < 0 || draft.lowMax() >= draft.mediumMax() || draft.mediumMax() >= 100) {
            violations.add(new Violation("lowMax", "Requires 0 <= lowMax < mediumMax < 100."));
        }
        BigDecimal coverage = draft.minCoverage();
        if (coverage == null || coverage.signum() <= 0 || coverage.compareTo(HUNDRED) > 0 || tooPrecise(coverage)) {
            violations.add(new Violation("minCoverage", "Must be above 0 and at most 100.0000, with 4 decimals."));
        }
        return List.copyOf(violations);
    }

    private static void validateFactors(List<FactorDefinition> factors, List<Violation> violations) {
        if (factors == null || factors.size() != FactorId.values().length) {
            violations.add(new Violation("factors", "Exactly the four factors of the catalogue are required."));
            return;
        }
        Set<FactorId> seen = EnumSet.noneOf(FactorId.class);
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < factors.size(); i++) {
            FactorDefinition factor = factors.get(i);
            String prefix = "factors[" + i + "]";
            if (factor == null || factor.factorId() == null || !seen.add(factor.factorId())) {
                violations.add(new Violation(prefix + ".factorId", "Each factor of the catalogue exactly once."));
                continue;
            }
            BigDecimal weight = factor.weight();
            if (weight == null || weight.signum() <= 0 || tooPrecise(weight)) {
                violations.add(new Violation(prefix + ".weight", "Must be above 0, with at most 4 decimals."));
            } else {
                total = total.add(weight);
            }
            validateCurve(prefix + ".curve", factor.curve(), violations);
        }
        if (violations.isEmpty() && total.compareTo(HUNDRED) != 0) {
            violations.add(new Violation("factors", "The weights must add up to exactly 100.0000."));
        }
    }

    private static void validateCurve(String field, List<CurvePoint> curve, List<Violation> violations) {
        if (curve == null || curve.size() < MIN_POINTS || curve.size() > MAX_POINTS) {
            violations.add(new Violation(field, "A curve has 2 to 8 points."));
            return;
        }
        BigDecimal previous = null;
        for (CurvePoint point : curve) {
            boolean valid = point != null
                    && point.input() != null
                    && point.input().signum() >= 0
                    && (previous == null || point.input().compareTo(previous) > 0)
                    && point.risk() >= 0
                    && point.risk() <= 100;
            if (!valid) {
                violations.add(new Violation(
                        field, "Inputs must be non-negative and strictly increasing; risks from 0 to 100."));
                return;
            }
            previous = point.input();
        }
    }

    private static boolean tooPrecise(BigDecimal value) {
        return value.stripTrailingZeros().scale() > MAX_SCALE;
    }
}
