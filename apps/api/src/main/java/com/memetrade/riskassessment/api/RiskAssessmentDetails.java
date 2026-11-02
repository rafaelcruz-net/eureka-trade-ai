package com.memetrade.riskassessment.api;

import com.memetrade.marketdata.api.TokenId;
import java.util.Optional;

/**
 * Detail of the assessment of one token for the side panel (F4, C2 {@code GET /api/v1/tokens/{chain}/{mint}/assessment}).
 */
public interface RiskAssessmentDetails {

    /**
     * The stored assessment with every factor; the stale flag of each input is computed now from the stored observation
     * time (BR3.6). A token observed in the last 24 hours without an assessment yet is computed, stored and returned.
     *
     * @return empty when the token does not exist or has no observation in the last 24 hours ({@code 404})
     */
    Optional<AssessmentDetail> detail(TokenId token);
}
