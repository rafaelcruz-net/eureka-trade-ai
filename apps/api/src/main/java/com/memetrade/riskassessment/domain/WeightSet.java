package com.memetrade.riskassessment.domain;

import com.memetrade.riskassessment.api.FactorDefinition;
import com.memetrade.riskassessment.api.FactorId;
import com.memetrade.riskassessment.api.WeightSetDraft;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** A stored, already validated weight version used by the calculation (BR4.1). */
public record WeightSet(
        int version, List<FactorDefinition> factors, int lowMax, int mediumMax, BigDecimal minCoverage) {

    public WeightSet {
        factors = List.copyOf(factors);
    }

    public static WeightSet of(int version, WeightSetDraft draft) {
        return new WeightSet(version, draft.factors(), draft.lowMax(), draft.mediumMax(), draft.minCoverage());
    }

    public Optional<FactorDefinition> factor(FactorId id) {
        return factors.stream().filter(f -> f.factorId() == id).findFirst();
    }

    public WeightSetDraft toDraft() {
        return new WeightSetDraft(factors, lowMax, mediumMax, minCoverage);
    }
}
