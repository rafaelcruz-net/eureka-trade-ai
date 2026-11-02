package com.memetrade.riskassessment.infra;

import com.memetrade.marketdata.api.MarketRiskInputs;
import com.memetrade.marketdata.api.MarketRiskInputs.TokenInputs;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.riskassessment.api.AssessmentDetail;
import com.memetrade.riskassessment.api.AssessmentResult;
import com.memetrade.riskassessment.api.RiskAssessmentDetails;
import com.memetrade.riskassessment.api.RiskAssessmentQuery;
import com.memetrade.riskassessment.api.UnavailableReason;
import com.memetrade.riskassessment.domain.FactorResult;
import com.memetrade.riskassessment.domain.RiskEvaluator;
import com.memetrade.riskassessment.domain.RiskEvaluator.Evaluation;
import com.memetrade.riskassessment.domain.StaleAtReading;
import com.memetrade.riskassessment.domain.WeightSet;
import com.memetrade.riskassessment.infra.JdbcRiskAssessmentStore.Stored;
import com.memetrade.riskassessment.infra.JdbcRiskAssessmentStore.Write;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * C12 ({@link RiskAssessmentQuery}) and the detail of F4 ({@link RiskAssessmentDetails}) over the stored assessments.
 * A token observed in the last 24 hours without an assessment yet (new token before the first F5) is computed, stored
 * and returned at once; the stale flag of each input is computed at reading (BR3.6).
 */
public class RiskAssessmentService implements RiskAssessmentQuery, RiskAssessmentDetails {

    private static final Logger log = LoggerFactory.getLogger(RiskAssessmentService.class);

    private final MarketRiskInputs market;
    private final JdbcRiskWeightStore weights;
    private final JdbcRiskAssessmentStore assessments;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RiskAssessmentService(
            MarketRiskInputs market,
            JdbcRiskWeightStore weights,
            JdbcRiskAssessmentStore assessments,
            TransactionTemplate transactions,
            Clock clock) {
        this.market = market;
        this.weights = weights;
        this.assessments = assessments;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public AssessmentResult assess(TokenId token) {
        try {
            Optional<Stored> stored = assessments.find(token).or(() -> computeAndStore(token));
            if (stored.isEmpty()) {
                return new AssessmentResult.Unavailable(UnavailableReason.SOURCE_UNAVAILABLE);
            }
            Stored assessment = stored.get();
            if (!assessment.available()) {
                return new AssessmentResult.Unavailable(
                        assessment.unavailableReason().orElseThrow());
            }
            return new AssessmentResult.Available(
                    assessment.score().orElseThrow(), assessment.band().orElseThrow(), assessment.version());
        } catch (DataAccessException failure) {
            log.error(
                    "Risk assessment query failed: exceptionClass={}",
                    failure.getClass().getSimpleName());
            return new AssessmentResult.Unavailable(UnavailableReason.SOURCE_UNAVAILABLE);
        }
    }

    @Override
    public Optional<AssessmentDetail> detail(TokenId token) {
        if (market.inputsFor(token).isEmpty()) {
            return Optional.empty();
        }
        return assessments.find(token).or(() -> computeAndStore(token)).map(this::detailOf);
    }

    private Optional<Stored> computeAndStore(TokenId token) {
        Optional<TokenInputs> inputs = market.inputsFor(token);
        if (inputs.isEmpty()) {
            return Optional.empty();
        }
        WeightSet active = weights.active();
        Evaluation evaluation = RiskEvaluator.evaluate(active, inputs.get());
        Instant now = clock.instant();
        transactions.executeWithoutResult(status ->
                assessments.write(List.of(new Write(token, evaluation.computation(), evaluation.fingerprint(), now))));
        return assessments.find(token);
    }

    private AssessmentDetail detailOf(Stored stored) {
        Instant now = clock.instant();
        BigDecimal minCoverage = weights.find(stored.version())
                .map(view -> view.definition().minCoverage())
                .orElseThrow();
        List<AssessmentDetail.Factor> factors = RiskJson.readResults(stored.factorsJson()).stream()
                .map(factor -> factor(factor, now))
                .toList();
        return new AssessmentDetail(
                stored.token(),
                stored.available(),
                stored.score(),
                stored.band(),
                stored.bandRaisedByUnknown(),
                stored.unavailableReason(),
                stored.coverage(),
                minCoverage,
                stored.version(),
                stored.decisionHint(),
                factors,
                stored.computedAt());
    }

    private static AssessmentDetail.Factor factor(FactorResult factor, Instant now) {
        return new AssessmentDetail.Factor(
                factor.factorId(),
                factor.weight(),
                StaleAtReading.apply(factor.input(), now),
                factor.factorRisk(),
                factor.contribution());
    }
}
