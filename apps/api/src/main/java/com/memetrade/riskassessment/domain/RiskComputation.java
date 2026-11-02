package com.memetrade.riskassessment.domain;

import com.memetrade.riskassessment.api.DecisionHint;
import com.memetrade.riskassessment.api.RiskBand;
import com.memetrade.riskassessment.api.UnavailableReason;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Output of {@link RiskCalculator} for one token and one weight version. Factors are kept in every case, also when
 * the indicator is unavailable (BR3.3, BR3.8).
 */
public record RiskComputation(
        int weightSetVersion,
        boolean available,
        Optional<Integer> score,
        Optional<RiskBand> band,
        boolean bandRaisedByUnknown,
        Optional<UnavailableReason> unavailableReason,
        BigDecimal coverage,
        BigDecimal minCoverage,
        DecisionHint decisionHint,
        List<FactorResult> factors) {

    public RiskComputation {
        factors = List.copyOf(factors);
    }
}
