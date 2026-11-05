package com.memetrade.decision.domain;

import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.PriceVolumeWindow;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.riskassessment.api.AssessmentResult;
import java.util.Objects;

/**
 * Everything the decision read about ONE token in the cycle (BR4.1, BR4.10): the window of C11, the reference price,
 * the decimals of the mint and the assessment of C12. Recorded as the {@code inputs} of the decision so it can be
 * recomputed (BR4.2).
 */
public record TokenInputs(
        TokenId token,
        PriceVolumeWindow window,
        MarketValue referencePrice,
        MarketValue decimals,
        AssessmentResult assessment) {

    public TokenInputs {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(referencePrice, "referencePrice");
        Objects.requireNonNull(decimals, "decimals");
        Objects.requireNonNull(assessment, "assessment");
    }
}
