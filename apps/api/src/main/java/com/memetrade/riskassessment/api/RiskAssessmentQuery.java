package com.memetrade.riskassessment.api;

import com.memetrade.marketdata.api.TokenId;

/**
 * Public read interface of the RiskAssessment module (contract C12), used by the scanner and by U5.
 *
 * <p>Rules: a synchronous call without retry; the stored assessment is read (computed and stored once when the token
 * has none yet); a technical failure becomes {@code Unavailable(SOURCE_UNAVAILABLE)}, never an exception and never a
 * low risk.
 */
public interface RiskAssessmentQuery {

    AssessmentResult assess(TokenId token);
}
