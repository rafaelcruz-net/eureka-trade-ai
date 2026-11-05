package com.memetrade.riskcontrol.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.Principal;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.riskcontrol.api.OrderSide;
import com.memetrade.riskcontrol.api.PauseTrigger;
import com.memetrade.riskcontrol.api.RejectionReason;
import com.memetrade.riskcontrol.domain.DetailUnit;
import com.memetrade.riskcontrol.domain.EvaluationRecord;
import com.memetrade.riskcontrol.domain.EvaluationRecord.Outcome;
import com.memetrade.riskcontrol.domain.EvaluationRepository;
import com.memetrade.riskcontrol.domain.MarketInputs;
import com.memetrade.riskcontrol.domain.ReasonDetail;
import com.memetrade.riskcontrol.domain.RejectionRecord;
import com.memetrade.riskcontrol.domain.RejectionRepository;
import com.memetrade.riskcontrol.domain.RejectionRepository.PageKey;
import com.memetrade.riskcontrol.domain.RejectionRepository.RejectionPage;
import com.memetrade.riskcontrol.domain.RiskFigure;
import com.memetrade.testsupport.AbstractU6IT;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 8 of U6, WRITTEN BEFORE THE CODE (BR2.8, BR4.1, BR4.2; NFR2.4, NFR5.1): the insertion that does not abort the
 * transaction on a conflict, the winner read back, the rejection with its details in one batch, the page by index and
 * cursor, the EXISTS by person and the filter by tenant, person and PAPER in every query.
 */
class RiskRepositoriesIT extends AbstractU6IT {

    private static final SourceRef SOURCE = new SourceRef("sim-u7", "Simulado U7", true);

    @Autowired
    private EvaluationRepository evaluations;

    @Autowired
    private RejectionRepository rejections;

    private Principal person;
    private UUID bot;

    @BeforeEach
    void seed() {
        person = newPerson();
        bot = UUID.randomUUID();
    }

    @Test
    void aConflictingInsertReturnsFalseAndTheTransactionKeepsWorking() {
        UUID decision = UUID.randomUUID();
        EvaluationRecord first = evaluation(person, decision, Outcome.APPROVED, clock.instant());
        EvaluationRecord second = evaluation(person, decision, Outcome.REJECTED, clock.instant());

        List<Boolean> inserted = cycleTransactions.execute(status -> {
            List<Boolean> results = new ArrayList<>();
            results.add(evaluations.insertIfAbsent(first));
            results.add(evaluations.insertIfAbsent(second));
            // The transaction is not aborted: a later statement still runs and the winner is read.
            results.add(evaluations.find(person.tenantId(), bot, decision).isPresent());
            return results;
        });

        assertThat(inserted).containsExactly(true, false, true);
        EvaluationRecord winner =
                evaluations.find(person.tenantId(), bot, decision).orElseThrow();
        assertThat(winner.evaluationId()).isEqualTo(first.evaluationId());
        assertThat(winner.outcome()).isEqualTo(Outcome.APPROVED);
        assertThat(winner.requestedQuantity()).isEqualByComparingTo("100.000000");
        assertThat(winner.limitsVersion()).isEqualTo(1);
        assertThat(winner.evaluatedAt()).isEqualTo(clock.instant());
    }

    @Test
    void theEvaluationOfADecisionIsFoundOnlyInItsTenant() {
        UUID decision = UUID.randomUUID();
        cycleTransactions.execute(
                s -> evaluations.insertIfAbsent(evaluation(person, decision, Outcome.APPROVED, clock.instant())));
        Principal stranger = newPerson();

        assertThat(evaluations.find(person.tenantId(), bot, decision)).isPresent();
        assertThat(evaluations.find(stranger.tenantId(), bot, decision)).isEmpty();
        assertThat(evaluations.find(person.tenantId(), UUID.randomUUID(), decision))
                .isEmpty();
        // The same decision of the same bot id in another tenant is a different evaluation.
        Boolean insertedElsewhere = cycleTransactions.execute(
                s -> evaluations.insertIfAbsent(evaluation(stranger, decision, Outcome.REJECTED, clock.instant())));
        assertThat(insertedElsewhere).isTrue();
    }

    @Test
    void existsForPersonSeesOnlyTheEvaluationsOfThePersonInTheTenant() {
        cycleTransactions.execute(s ->
                evaluations.insertIfAbsent(evaluation(person, UUID.randomUUID(), Outcome.APPROVED, clock.instant())));
        Principal colleague = samePersonTenant(person);
        Principal stranger = newPerson();

        assertThat(evaluations.existsForPerson(person.tenantId(), person.userId(), bot))
                .isTrue();
        assertThat(evaluations.existsForPerson(colleague.tenantId(), colleague.userId(), bot))
                .isFalse();
        assertThat(evaluations.existsForPerson(stranger.tenantId(), stranger.userId(), bot))
                .isFalse();
        assertThat(evaluations.existsForPerson(person.tenantId(), person.userId(), UUID.randomUUID()))
                .isFalse();
    }

    @Test
    void aRejectionIsStoredWithItsDetailsAndItsMarketInputsExactly() {
        Instant at = clock.instant();
        EvaluationRecord evaluation = evaluation(person, UUID.randomUUID(), Outcome.REJECTED, at);
        MarketInputs market = new MarketInputs(
                new MarketValue.Known(new BigDecimal("0.000012345678"), SOURCE, at.minusSeconds(61), false),
                new MarketValue.Unknown(UnknownReason.SOURCE_UNAVAILABLE, Optional.of(SOURCE)),
                RiskFigure.unknown(),
                RiskFigure.unknown());
        RejectionRecord rejection = rejection(
                evaluation,
                List.of(
                        new ReasonDetail(RejectionReason.MIN_LIQUIDITY, "UNKNOWN", "10000.000000", DetailUnit.USDC),
                        new ReasonDetail(RejectionReason.STALE_DATA, "61", "60", DetailUnit.SECONDS),
                        new ReasonDetail(RejectionReason.DAILY_LOSS, "50.000000", "50.000000", DetailUnit.USDC)),
                Set.of(PauseTrigger.DAILY_LOSS),
                market,
                at);

        cycleTransactions.execute(s -> {
            evaluations.insertIfAbsent(evaluation);
            rejections.insert(rejection);
            return null;
        });

        RejectionPage page = rejections.page(person.tenantId(), person.userId(), bot, Optional.empty(), 50);
        assertThat(page.items()).hasSize(1);
        RejectionRecord read = page.items().getFirst();
        assertThat(read.reasons())
                .containsExactly(RejectionReason.MIN_LIQUIDITY, RejectionReason.STALE_DATA, RejectionReason.DAILY_LOSS);
        assertThat(read.details()).isEqualTo(rejection.details());
        assertThat(read.pauseTriggers()).containsExactly(PauseTrigger.DAILY_LOSS);
        assertThat(read.market()).isEqualTo(market);
        assertThat(read.evaluationId()).isEqualTo(evaluation.evaluationId());
        assertThat(read.decisionId()).isEqualTo(evaluation.decisionId());
        assertThat(read.limitsVersion()).isEqualTo(1);
        assertThat(read.evaluatedAt()).isEqualTo(at);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.totalCapped()).isFalse();
        assertThat(page.next()).isEmpty();
    }

    @Test
    void thePageIsNewestFirstAndTheCursorContinuesWithoutGapsOrRepeats() {
        List<UUID> written = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Instant at = clock.instant().plusSeconds(i % 3);
            EvaluationRecord evaluation = evaluation(person, UUID.randomUUID(), Outcome.REJECTED, at);
            RejectionRecord rejection = simpleRejection(evaluation, at);
            cycleTransactions.execute(s -> {
                evaluations.insertIfAbsent(evaluation);
                rejections.insert(rejection);
                return null;
            });
            written.add(rejection.rejectionId());
        }

        RejectionPage first = rejections.page(person.tenantId(), person.userId(), bot, Optional.empty(), 2);
        RejectionPage second = rejections.page(person.tenantId(), person.userId(), bot, first.next(), 2);
        RejectionPage third = rejections.page(person.tenantId(), person.userId(), bot, second.next(), 2);

        List<RejectionRecord> all = new ArrayList<>(first.items());
        all.addAll(second.items());
        all.addAll(third.items());
        assertThat(all).extracting(RejectionRecord::rejectionId).containsExactlyInAnyOrderElementsOf(written);
        for (int i = 1; i < all.size(); i++) {
            RejectionRecord before = all.get(i - 1);
            RejectionRecord after = all.get(i);
            // PostgreSQL orders uuid by its unsigned bytes, which is the order of the canonical text (UUID.compareTo
            // of Java compares signed longs and differs).
            boolean ordered = before.evaluatedAt().isAfter(after.evaluatedAt())
                    || (before.evaluatedAt().equals(after.evaluatedAt())
                            && before.rejectionId()
                                            .toString()
                                            .compareTo(after.rejectionId().toString())
                                    > 0);
            assertThat(ordered).as("item " + i + " is older than the previous").isTrue();
        }
        assertThat(first.total()).isEqualTo(5);
        assertThat(third.items()).hasSize(1);
        assertThat(third.next()).isEmpty();
        assertThat(first.next())
                .contains(new PageKey(
                        first.items().get(1).evaluatedAt(), first.items().get(1).rejectionId()));
    }

    @Test
    void thePageNeverShowsRejectionsOfAnotherPersonOrTenantForTheSameBotId() {
        Principal colleague = samePersonTenant(person);
        Principal stranger = newPerson();
        for (Principal owner : List.of(person, colleague, stranger)) {
            EvaluationRecord evaluation = evaluation(owner, UUID.randomUUID(), Outcome.REJECTED, clock.instant());
            cycleTransactions.execute(s -> {
                evaluations.insertIfAbsent(evaluation);
                rejections.insert(simpleRejection(evaluation, clock.instant()));
                return null;
            });
        }

        RejectionPage mine = rejections.page(person.tenantId(), person.userId(), bot, Optional.empty(), 50);

        assertThat(mine.items()).hasSize(1).allMatch(r -> r.ownerUserId().equals(person.userId()));
        assertThat(mine.total()).isEqualTo(1);
        assertThat(rejections
                        .page(stranger.tenantId(), person.userId(), bot, Optional.empty(), 50)
                        .items())
                .isEmpty();
    }

    @Test
    void anEmptyPageWhenEveryEvaluationWasApproved() {
        cycleTransactions.execute(s ->
                evaluations.insertIfAbsent(evaluation(person, UUID.randomUUID(), Outcome.APPROVED, clock.instant())));

        RejectionPage page = rejections.page(person.tenantId(), person.userId(), bot, Optional.empty(), 50);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.next()).isEmpty();
    }

    /** The batches used by the preparation of the load scenario write the same rows as one by one. */
    @Test
    void manyEvaluationsAndRejectionsAreWrittenInBatchesWithTheSameShape() {
        List<EvaluationRecord> batchEvaluations = new ArrayList<>();
        List<RejectionRecord> batchRejections = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            EvaluationRecord evaluation = evaluation(
                    person, UUID.randomUUID(), Outcome.REJECTED, clock.instant().minusSeconds(i));
            batchEvaluations.add(evaluation);
            batchRejections.add(rejection(
                    evaluation,
                    List.of(
                            new ReasonDetail(RejectionReason.ORDER_SIZE, "100.000001", "100.000000", DetailUnit.USDC),
                            new ReasonDetail(RejectionReason.DAILY_LOSS, "50.000000", "50.000000", DetailUnit.USDC)),
                    Set.of(PauseTrigger.DAILY_LOSS),
                    market(),
                    evaluation.evaluatedAt()));
        }

        cycleTransactions.execute(s -> {
            evaluations.insertAll(batchEvaluations);
            rejections.insertAll(batchRejections);
            return null;
        });

        RejectionPage page = rejections.page(person.tenantId(), person.userId(), bot, Optional.empty(), 50);
        assertThat(page.items())
                .extracting(RejectionRecord::rejectionId)
                .containsExactlyInAnyOrderElementsOf(batchRejections.stream()
                        .map(RejectionRecord::rejectionId)
                        .toList());
        assertThat(page.items())
                .allMatch(r -> r.details().size() == 2 && r.market().equals(market()));
        assertThat(evaluations.existsForPerson(person.tenantId(), person.userId(), bot))
                .isTrue();
    }

    @Test
    void aDuplicateRejectionCitesTheOriginalEvaluation() {
        EvaluationRecord original = evaluation(person, UUID.randomUUID(), Outcome.APPROVED, clock.instant());
        RejectionRecord duplicate = rejection(
                original,
                List.of(new ReasonDetail(
                        RejectionReason.DUPLICATE,
                        original.decisionId().toString(),
                        original.evaluationId().toString(),
                        DetailUnit.REFERENCE)),
                Set.of(),
                market(),
                clock.instant());

        cycleTransactions.execute(s -> {
            evaluations.insertIfAbsent(original);
            rejections.insert(duplicate);
            return null;
        });

        RejectionRecord read = rejections
                .page(person.tenantId(), person.userId(), bot, Optional.empty(), 50)
                .items()
                .getFirst();
        assertThat(read.reasons()).containsExactly(RejectionReason.DUPLICATE);
        assertThat(read.details().getFirst().limit())
                .isEqualTo(original.evaluationId().toString());
        assertThat(read.pauseTriggers()).isEmpty();
    }

    private EvaluationRecord evaluation(Principal owner, UUID decision, Outcome outcome, Instant at) {
        return new EvaluationRecord(
                UUID.randomUUID(),
                owner.tenantId(),
                owner.userId(),
                bot,
                decision,
                OrderSide.BUY,
                "solana",
                "SyntheticMint" + decision.toString().substring(0, 8),
                new BigDecimal("100.000000"),
                1,
                outcome,
                at);
    }

    private RejectionRecord simpleRejection(EvaluationRecord evaluation, Instant at) {
        return rejection(
                evaluation,
                List.of(new ReasonDetail(RejectionReason.ORDER_SIZE, "100.000001", "100.000000", DetailUnit.USDC)),
                Set.of(),
                market(),
                at);
    }

    private static RejectionRecord rejection(
            EvaluationRecord evaluation,
            List<ReasonDetail> details,
            Set<PauseTrigger> triggers,
            MarketInputs market,
            Instant at) {
        return new RejectionRecord(
                UUID.randomUUID(),
                evaluation.tenantId(),
                evaluation.ownerUserId(),
                evaluation.botId(),
                evaluation.decisionId(),
                evaluation.evaluationId(),
                evaluation.side(),
                evaluation.chain(),
                evaluation.tokenMint(),
                evaluation.requestedQuantity(),
                details,
                triggers,
                evaluation.limitsVersion(),
                market,
                at);
    }

    private MarketInputs market() {
        return new MarketInputs(
                new MarketValue.Known(new BigDecimal("1.000000000000"), SOURCE, clock.instant(), false),
                new MarketValue.Known(new BigDecimal("10000.000000"), SOURCE, clock.instant(), false),
                RiskFigure.known(new BigDecimal("1.5000")),
                RiskFigure.known(new BigDecimal("100.000001")));
    }
}
