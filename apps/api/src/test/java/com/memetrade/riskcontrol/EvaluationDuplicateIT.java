package com.memetrade.riskcontrol;

import static com.memetrade.riskcontrol.RiskGateFixtures.baseLimits;
import static com.memetrade.riskcontrol.RiskGateFixtures.order;
import static com.memetrade.riskcontrol.RiskGateFixtures.rejectedEvents;
import static com.memetrade.testsupport.LedgerRows.sqlState;
import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.Principal;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.riskcontrol.api.CandidateOrder;
import com.memetrade.riskcontrol.api.GateResult;
import com.memetrade.riskcontrol.api.GateResult.Approved;
import com.memetrade.riskcontrol.api.GateResult.Rejected;
import com.memetrade.riskcontrol.api.OrderGate;
import com.memetrade.riskcontrol.api.OrderSide;
import com.memetrade.riskcontrol.api.RejectionReason;
import com.memetrade.testsupport.AbstractU6IT;
import com.memetrade.testsupport.CycleAuthContext;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Passo 9 of U6, WRITTEN BEFORE THE CODE (BR2.8, F2; NFR4.4): one evaluation per decision. E8 in sequence; E20 with two
 * cycle transactions of the same decision overlapping on purpose (the second insert waits for the first), 50
 * repetitions in READ COMMITTED, plus one attempt in REPEATABLE READ that shows the expected serialization error; E21
 * after a rollback. Never a database error in READ COMMITTED, never a second evaluation.
 */
class EvaluationDuplicateIT extends AbstractU6IT {

    private static final int REPETITIONS = 50;

    @Autowired
    private OrderGate gate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Principal person;
    private TokenId token;
    private UUID bot;
    private ExecutorService threads;

    @BeforeEach
    void seed() {
        person = newPerson();
        token = newToken();
        marketNow(token, "1.000000000000", "10000.000000");
        bot = fundedBot(person, "1000.000000");
        threads = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @Test
    void e8TheSecondEvaluationOfTheSameDecisionIsADuplicateCitingTheFirst() {
        CandidateOrder order = order(bot, token, OrderSide.BUY, "100.000000");
        assertThat(inCycle(person, () -> gate.evaluate(order, baseLimits()))).isEqualTo(new Approved(order));
        UUID original = evaluationId(order.decisionId());

        Rejected duplicate = (Rejected) inCycle(person, () -> gate.evaluate(order, baseLimits()));

        assertThat(duplicate.reasons()).containsExactly(RejectionReason.DUPLICATE);
        assertThat(duplicate.pauseTriggers()).isEmpty();
        assertThat(duplicate.limitsVersion()).isEqualTo(1);
        assertThat(RiskGateFixtures.evaluations(jdbc, order.decisionId())).isEqualTo(1);
        Map<String, Object> rejection = jdbc.sql("select * from order_rejection where rejection_id = :r")
                .param("r", duplicate.rejectionId())
                .query()
                .singleRow();
        assertThat(rejection.get("evaluation_id")).isEqualTo(original);
        Map<String, Object> detail = jdbc.sql("select * from rejection_reason_detail where rejection_id = :r")
                .param("r", duplicate.rejectionId())
                .query()
                .singleRow();
        assertThat(detail.get("measured")).isEqualTo(order.decisionId().toString());
        assertThat(detail.get("limit_value")).isEqualTo(original.toString());
        assertThat(detail.get("unit")).isEqualTo("REFERENCE");
        assertThat(rejectedEvents(jdbc, bot)).isEqualTo(1);
    }

    @Test
    void e8AReEvaluationOfARejectedDecisionIsOnlyADuplicateEvenWhenTheLimitsWouldNowPass() {
        CandidateOrder tooBig = order(bot, token, OrderSide.BUY, "100.000001");
        Rejected first = (Rejected) inCycle(person, () -> gate.evaluate(tooBig, baseLimits()));
        assertThat(first.reasons()).containsExactly(RejectionReason.ORDER_SIZE);

        Rejected second =
                (Rejected) inCycle(person, () -> gate.evaluate(tooBig, RiskGateFixtures.limits("200.000000")));

        assertThat(second.reasons()).containsExactly(RejectionReason.DUPLICATE);
        assertThat(RiskGateFixtures.evaluations(jdbc, tooBig.decisionId())).isEqualTo(1);
        assertThat(RiskGateFixtures.rejections(jdbc, tooBig.decisionId())).isEqualTo(2);
        assertThat(rejectedEvents(jdbc, bot)).isEqualTo(2);
    }

    /** E20: 50 races of the same decision; the loser waits for the winner's commit and becomes DUPLICATE. */
    @Test
    void e20TwoCycleTransactionsOfTheSameDecisionLeaveOneEvaluationInReadCommitted() throws Exception {
        for (int i = 0; i < REPETITIONS; i++) {
            CandidateOrder order = order(bot, token, OrderSide.BUY, "1.000000");

            Race race = race(order, cycleTransactions, true);

            assertThat(race.first()).as("repetition " + i).isEqualTo(new Approved(order));
            assertThat(race.second()).as("repetition " + i).isInstanceOf(Rejected.class);
            assertThat(((Rejected) race.second()).reasons()).containsExactly(RejectionReason.DUPLICATE);
            assertThat(RiskGateFixtures.evaluations(jdbc, order.decisionId())).isEqualTo(1);
            assertThat(jdbc.sql("select evaluation_id from order_rejection where decision_id = :d")
                            .param("d", order.decisionId())
                            .query(UUID.class)
                            .single())
                    .isEqualTo(evaluationId(order.decisionId()));
        }
    }

    /** F2 step 5: when the first transaction rolls back, the waiting one inserts and is the first evaluation. */
    @Test
    void whenTheFirstOfTheRaceRollsBackTheSecondIsTheFirstEvaluation() throws Exception {
        CandidateOrder order = order(bot, token, OrderSide.BUY, "1.000000");

        Race race = race(order, cycleTransactions, false);

        assertThat(race.second()).isEqualTo(new Approved(order));
        assertThat(RiskGateFixtures.evaluations(jdbc, order.decisionId())).isEqualTo(1);
        assertThat(RiskGateFixtures.rejections(jdbc, order.decisionId())).isZero();
    }

    /** The cycle runs in READ COMMITTED (decision of the plan); REPEATABLE READ fails the loser with 40001. */
    @Test
    void inRepeatableReadTheLoserFailsWithASerializationErrorAndWritesNothing() throws Exception {
        CandidateOrder order = order(bot, token, OrderSide.BUY, "1.000000");
        TransactionTemplate repeatable = new TransactionTemplate(transactionManager);
        repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        repeatable.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Race race = race(order, repeatable, true);

        assertThat(race.first()).isEqualTo(new Approved(order));
        assertThat(race.secondFailure()).isNotNull();
        assertThat(sqlState(race.secondFailure())).isEqualTo("40001");
        assertThat(RiskGateFixtures.evaluations(jdbc, order.decisionId())).isEqualTo(1);
        assertThat(RiskGateFixtures.rejections(jdbc, order.decisionId())).isZero();
    }

    @Test
    void e21AfterARollbackTheDecisionIsEvaluatedAgainAsTheFirst() {
        CandidateOrder order = order(bot, token, OrderSide.BUY, "100.000000");
        try {
            inCycle(person, () -> {
                gate.evaluate(order, baseLimits());
                throw new IllegalStateException("technical failure of the cycle after the barrier");
            });
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessageContaining("technical failure");
        }
        assertThat(RiskGateFixtures.evaluations(jdbc, order.decisionId())).isZero();

        GateResult again = inCycle(person, () -> gate.evaluate(order, baseLimits()));

        assertThat(again).isEqualTo(new Approved(order));
        assertThat(RiskGateFixtures.evaluations(jdbc, order.decisionId())).isEqualTo(1);
    }

    // --- the forced race -------------------------------------------------------------------------------------------

    private record Race(GateResult first, GateResult second, Throwable secondFailure) {}

    /**
     * The first transaction evaluates and holds its uncommitted evaluation; the second (in {@code secondTransactions})
     * starts, does not see it and waits on the unique index; then the first commits (or rolls back) and the second
     * finishes. Waiting is observed in pg_stat_activity, never by sleeping a fixed time.
     */
    private Race race(CandidateOrder order, TransactionTemplate secondTransactions, boolean firstCommits)
            throws Exception {
        CountDownLatch evaluated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<GateResult> first = threads.submit(() -> CycleAuthContext.as(person, () -> {
            try {
                return cycleTransactions.execute(status -> {
                    GateResult result = gate.evaluate(order, baseLimits());
                    evaluated.countDown();
                    awaitQuietly(release);
                    if (!firstCommits) {
                        status.setRollbackOnly();
                    }
                    return result;
                });
            } finally {
                evaluated.countDown();
            }
        }));
        assertThat(evaluated.await(30, TimeUnit.SECONDS)).isTrue();
        Future<GateResult> second = threads.submit(() -> CycleAuthContext.as(
                person, () -> secondTransactions.execute(status -> gate.evaluate(order, baseLimits()))));
        awaitInsertWaiting();
        release.countDown();
        GateResult firstResult = first.get(30, TimeUnit.SECONDS);
        try {
            return new Race(firstResult, second.get(30, TimeUnit.SECONDS), null);
        } catch (ExecutionException failed) {
            return new Race(firstResult, null, failed.getCause());
        }
    }

    private void awaitInsertWaiting() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.sql("""
                            select count(*) from pg_stat_activity
                            where datname = current_database() and wait_event_type = 'Lock'
                              and query ilike '%insert into order_evaluation%'
                            """).query(Long.class).single();
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("The second transaction never waited on the unique index of the decision");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The race was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private UUID evaluationId(UUID decisionId) {
        return jdbc.sql("select evaluation_id from order_evaluation where decision_id = :d")
                .param("d", decisionId)
                .query(UUID.class)
                .single();
    }
}
