/**
 * Public interface of the RiskAssessment module (U4), the only package other modules may use.
 *
 * <ul>
 *   <li>{@link com.memetrade.riskassessment.api.RiskAssessmentQuery} (contract C12): the stored assessment of a token,
 *       {@code Available} or {@code Unavailable}; never a low risk for an unavailable indicator.
 *   <li>{@link com.memetrade.riskassessment.api.RiskAssessmentDetails}: how an assessment was made, for the side
 *       panel of the guest app.
 *   <li>{@link com.memetrade.riskassessment.api.RiskWeightAdministration}: weight versions for the administrative
 *       screen of U8 (administrator only, audited activation).
 * </ul>
 *
 * <p>The indicator is "in validation": factors, weights and bands of version 1 are a hypothesis to calibrate
 * (docs/adr/ADR-0005). Every value is an exact decimal; never floating point.
 */
package com.memetrade.riskassessment.api;
