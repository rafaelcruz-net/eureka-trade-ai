package com.memetrade.riskassessment.api;

/**
 * Result of {@link RiskAssessmentQuery} (contract C12): {@link Available} with the indicator, its band and the weight
 * version, or {@link Unavailable} with the reason. An unavailable indicator is never a low risk.
 */
public sealed interface AssessmentResult permits AssessmentResult.Available, AssessmentResult.Unavailable {

    record Available(int score, RiskBand band, int weightSetVersion) implements AssessmentResult {}

    record Unavailable(UnavailableReason reason) implements AssessmentResult {}
}
