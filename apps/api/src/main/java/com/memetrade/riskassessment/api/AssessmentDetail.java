package com.memetrade.riskassessment.api;

import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * How an assessment was made (BR3.9): indicator or unavailability with its reason, coverage and minimum coverage,
 * weight version, decision hint, whether the band was raised by an unknown factor, the computation time and every
 * factor. The indicator is always "in validation" (C2 {@code inValidation = true}).
 *
 * @param score present only when AVAILABLE
 * @param band present only when AVAILABLE
 * @param unavailableReason present only when UNAVAILABLE
 */
public record AssessmentDetail(
        TokenId token,
        boolean available,
        Optional<Integer> score,
        Optional<RiskBand> band,
        boolean bandRaisedByUnknown,
        Optional<UnavailableReason> unavailableReason,
        BigDecimal coverage,
        BigDecimal minCoverage,
        int weightSetVersion,
        DecisionHint decisionHint,
        List<Factor> factors,
        Instant computedAt) {

    public AssessmentDetail {
        factors = List.copyOf(factors);
    }

    /**
     * One factor: weight, input (KNOWN with source, observation time and a stale flag computed at READ time, or UNKNOWN
     * with its reason), the factor risk and the contribution in indicator points (empty when the input is UNKNOWN).
     */
    public record Factor(
            FactorId factorId,
            BigDecimal weight,
            MarketValue input,
            int factorRisk,
            Optional<BigDecimal> contribution) {}
}
