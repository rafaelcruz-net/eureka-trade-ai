package com.memetrade.testsupport;

import com.memetrade.marketdata.api.TokenId;
import com.memetrade.riskassessment.api.AssessmentResult;
import com.memetrade.riskassessment.api.RiskAssessmentQuery;
import com.memetrade.riskassessment.api.RiskBand;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** C12 double of the U5 tests: the band of each scripted token on command; other tokens use the real U4 query. */
public final class U5Assessments implements RiskAssessmentQuery {

    private final RiskAssessmentQuery real;
    private final Map<TokenId, AssessmentResult> scripted = new ConcurrentHashMap<>();

    public U5Assessments(RiskAssessmentQuery real) {
        this.real = real;
    }

    public void band(TokenId token, RiskBand band) {
        scripted.put(token, new AssessmentResult.Available(band == RiskBand.HIGH ? 80 : 20, band, 1));
    }

    public void unavailable(TokenId token) {
        scripted.put(
                token,
                new AssessmentResult.Unavailable(
                        com.memetrade.riskassessment.api.UnavailableReason.SOURCE_UNAVAILABLE));
    }

    public void clear() {
        scripted.clear();
    }

    @Override
    public AssessmentResult assess(TokenId token) {
        AssessmentResult result = scripted.get(token);
        return result == null ? real.assess(token) : result;
    }
}
