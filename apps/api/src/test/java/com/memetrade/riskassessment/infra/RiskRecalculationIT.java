package com.memetrade.riskassessment.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketRiskInputs;
import com.memetrade.marketdata.api.MarketSourceAdapter.TokenInfo;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus.State;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.domain.MarketStore;
import com.memetrade.marketdata.domain.MarketStore.CollectionWrite;
import com.memetrade.marketdata.domain.MarketStore.ObservedValue;
import com.memetrade.riskassessment.api.AssessmentDetail;
import com.memetrade.riskassessment.api.AssessmentResult;
import com.memetrade.riskassessment.domain.RiskComputation;
import com.memetrade.riskassessment.domain.RiskEvaluator;
import com.memetrade.riskassessment.domain.WeightSet;
import com.memetrade.riskassessment.domain.WeightsV1;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** F5 and F4 against PostgreSQL (Step 10): no needless rewrite, version guard, batches, volume and reproducibility. */
@Tag("u4")
class RiskRecalculationIT extends AbstractPostgresIT {

    @Autowired
    private RiskRecalculator recalculator;

    @Autowired
    private RiskAssessmentService service;

    @Autowired
    private MarketStore store;

    @Autowired
    private MarketRiskInputs market;

    @Autowired
    private JdbcRiskWeightStore weights;

    @Autowired
    private JdbcRiskAssessmentStore assessments;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private LockingTaskExecutor locks;

    @Autowired
    private NamedParameterJdbcTemplate named;

    private SourceRef source;

    @BeforeEach
    void ownSource() {
        source = new SourceRef("rr-" + UUID.randomUUID().toString().substring(0, 8), "Recalc", true);
        store.registerSource(source, Duration.ofSeconds(30));
        store.updateSourceStatus(
                source.sourceId(), State.OK, Optional.empty(), clock.instant(), Optional.of(clock.instant()));
    }

    @Test
    void aSecondRunWithTheSameInputsRewritesNothing() {
        TokenId token = seed(1).getFirst();
        recalculator.recalculateNow();

        RiskRecalculator.Run second = recalculator.recalculateNow().orElseThrow();

        assertThat(second.rewritten()).isZero();
        assertThat(assessments.find(token)).isPresent();
    }

    @Test
    void anOlderVersionNeverOverwritesANewerOne() {
        TokenId token = seed(1).getFirst();
        int newer = transactions.execute(status -> weights.insertDraft(WeightsV1.draft(), "test", clock.instant()));
        WeightSet newerSet = WeightSet.of(newer, WeightsV1.draft());
        RiskEvaluator.Evaluation evaluation =
                RiskEvaluator.evaluate(newerSet, market.inputsFor(token).orElseThrow());
        transactions.executeWithoutResult(status -> assessments.write(List.of(new JdbcRiskAssessmentStore.Write(
                token, evaluation.computation(), evaluation.fingerprint(), clock.instant()))));

        recalculator.recalculateNow();

        assertThat(assessments.find(token).orElseThrow().version()).isEqualTo(newer);
    }

    @Test
    void aFailingBatchKeepsTheEarlierBatchesAndTheRunIsReportedFailed() {
        List<TokenId> tokens = seed(3);
        AtomicInteger calls = new AtomicInteger();
        JdbcRiskAssessmentStore failingSecond =
                new JdbcRiskAssessmentStore(JdbcClient.create(named.getJdbcTemplate()), named) {
                    @Override
                    public int write(List<Write> writes) {
                        if (calls.incrementAndGet() == 2) {
                            throw new IllegalStateException("injected failure in the second batch");
                        }
                        return super.write(writes);
                    }
                };
        RiskRecalculator oneByOne = new RiskRecalculator(
                new RiskRecalculator.Settings(
                        market, weights, failingSecond, transactions, locks, Runnable::run, clock, 1, false),
                new SimpleMeterRegistry());

        RiskRecalculator.Run run = oneByOne.recalculateNow().orElseThrow();

        assertThat(run.failed()).isTrue();
        assertThat(run.rewritten()).isEqualTo(1);
        assertThat(oneByOne.lastRunFailed()).isTrue();
        assertThat(tokens).isNotEmpty();
    }

    @Test
    void twoThousandAssessmentsAreComputedAndStoredWithinTenSeconds() {
        seed(2_000);

        long started = System.nanoTime();
        RiskRecalculator.Run run = recalculator.recalculateNow().orElseThrow();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(run.evaluated()).isGreaterThanOrEqualTo(2_000);
        assertThat(took).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void aStoredAssessmentIsReproducedFromItsStoredInputs() {
        TokenId token = seed(1).getFirst();
        recalculator.recalculateNow();
        JdbcRiskAssessmentStore.Stored stored = assessments.find(token).orElseThrow();

        RiskComputation again = RiskEvaluator.recalculate(
                WeightSet.of(
                        stored.version(),
                        weights.find(stored.version()).orElseThrow().definition()),
                RiskJson.readResults(stored.factorsJson()),
                false);

        assertThat(again.score()).isEqualTo(stored.score());
        assertThat(again.available()).isEqualTo(stored.available());
        assertThat(again.coverage()).isEqualByComparingTo(stored.coverage());
    }

    @Test
    void theFirstDetailOfANewTokenIsComputedStoredAndMarksStaleAtReading() {
        TokenId token = seed(1).getFirst();

        AssessmentDetail fresh = service.detail(token).orElseThrow();
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        AssessmentDetail later = service.detail(token).orElseThrow();

        assertThat(assessments.find(token)).isPresent();
        assertThat(fresh.factors()).hasSize(4);
        assertThat(((MarketValue.Known) fresh.factors().getFirst().input()).stale())
                .isFalse();
        assertThat(((MarketValue.Known) later.factors().getFirst().input()).stale())
                .isTrue();
        assertThat(later.computedAt()).isEqualTo(fresh.computedAt());
        assertThat(fresh.minCoverage()).isEqualByComparingTo("80.0000");
    }

    @Test
    void aTokenWithoutRecentObservationsHasNoDetailAndC12AnswersUnavailable() {
        TokenId unknown = new TokenId("solana", SyntheticIds.mint());

        assertThat(service.detail(unknown)).isEmpty();
        assertThat(service.assess(unknown)).isInstanceOf(AssessmentResult.Unavailable.class);
    }

    @Test
    void withEverySourceOfTheTokenFailedC12IsUnavailableSourceUnavailable() {
        TokenId token = seed(1).getFirst();
        store.updateSourceStatus(source.sourceId(), State.FAILED, Optional.empty(), clock.instant(), Optional.empty());

        AssessmentResult result = service.assess(token);

        assertThat(result)
                .isEqualTo(new AssessmentResult.Unavailable(
                        com.memetrade.riskassessment.api.UnavailableReason.SOURCE_UNAVAILABLE));
    }

    /** Tokens with every field KNOWN from this test's own source, written like a collection. */
    private List<TokenId> seed(int count) {
        Instant now = clock.instant();
        List<TokenInfo> tokens = new ArrayList<>();
        Map<TokenId, Map<MarketField, ObservedValue>> values = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            TokenId token = new TokenId("solana", SyntheticIds.mint());
            tokens.add(new TokenInfo(token, "R" + (i % 100), null, 6));
            Map<MarketField, ObservedValue> fields = new EnumMap<>(MarketField.class);
            fields.put(MarketField.PRICE, known("0.00" + (100 + i % 900), now));
            fields.put(MarketField.LIQUIDITY, known(Integer.toString(5_000 + i), now));
            fields.put(MarketField.VOLUME_24H, known(Integer.toString(20_000 + i), now));
            fields.put(MarketField.HOLDERS, known(Integer.toString(300 + i % 50), now));
            fields.put(MarketField.CREATED_AT, known(Long.toString(now.getEpochSecond() - 600), now));
            values.put(token, fields);
        }
        transactions.executeWithoutResult(
                status -> store.writeCollection(new CollectionWrite(source.sourceId(), now, tokens, values)));
        return tokens.stream().map(TokenInfo::token).toList();
    }

    private ObservedValue known(String value, Instant at) {
        return new ObservedValue(new MarketValue.Known(new BigDecimal(value), source, at, false), at);
    }
}
