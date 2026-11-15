package com.memetrade.bots.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.realexecution.api.RealDecision;
import com.memetrade.realexecution.api.RealSide;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * Step 7 of the U4, written BEFORE the code (BR1.2, BR2.2, F1, AC3.3.3, adendo da U3 "Posse do bot"): the REAL path of
 * the cycle. The cycle asks M6 for an order in progress, checks the ownership of the bot, calls the barrier (M5) in its
 * transaction and records the approved decision (M6 {@code recordDecision}, MANDATORY). Nothing of the PAPER ledger is
 * ever written for a REAL bot.
 */
@Tag("u4-real-execution")
class RealBotDecisionIT extends AbstractRealExecutionIT {

    private RealDecisionStep.Outcome submit(RealDecisionStep.Candidate candidate) {
        return transactions.execute(status -> decisionStep.submit(candidate));
    }

    private static RealDecisionStep.Candidate buy(Setup setup, long lamports) {
        return RealDecisionStep.Candidate.buy(
                setup.tenantId(), setup.userId(), setup.botId(), UUID.randomUUID(), setup.mint(), lamports, "sinal");
    }

    private long decisionRequests(UUID botId) {
        return jdbc.sql("select count(*) from real_decision_request where bot_id = :b")
                .param("b", botId)
                .query(Long.class)
                .single();
    }

    private long evaluations(UUID botId) {
        return jdbc.sql("select count(*) from real_risk_evaluation where bot_id = :b")
                .param("b", botId)
                .query(Long.class)
                .single();
    }

    @Test
    void anApprovedBuyIsRecordedAsAPendingDecisionWithTheReservationOfTheBarrier() {
        Setup setup = realBot("AUTO_FULL");

        RealDecisionStep.Outcome outcome = submit(buy(setup, 50_000_000L));

        assertThat(outcome.result()).isEqualTo("RECORDED");
        var row = jdbc.sql("select state, side, input_lamports, approval_id, reason from real_decision_request"
                        + " where decision_request_id = :id")
                .param("id", outcome.decisionRequestId().orElseThrow())
                .query()
                .singleRow();
        assertThat(row.get("state")).isEqualTo("PENDING");
        assertThat(row.get("side")).isEqualTo("BUY");
        assertThat(row.get("input_lamports")).isEqualTo(50_000_000L);
        assertThat(row.get("approval_id")).isNotNull();
        assertThat(row.get("reason")).isEqualTo("sinal");
    }

    @Test
    void s3ABotWithAnOrderInProgressDiscardsTheDecisionBeforeTheBarrier() {
        Setup setup = realBot("AUTO_FULL");
        consumer.runOnce();
        submit(buy(setup, 10_000_000L));
        consumer.runOnce();
        long before = evaluations(setup.botId());

        RealDecisionStep.Outcome outcome = submit(buy(setup, 10_000_000L));

        assertThat(outcome.result()).isEqualTo("ORDER_IN_PROGRESS");
        assertThat(outcome.decisionRequestId()).isEmpty();
        assertThat(evaluations(setup.botId())).isEqualTo(before);
    }

    @Test
    void anActiveLiquidationDiscardsEveryDecisionOfTheRealBotsOfThePerson() {
        Setup setup = realBot("AUTO_FULL");
        jdbc.sql("insert into real_liquidation (liquidation_id, tenant_id, user_id, state, created_at, updated_at)"
                        + " values (gen_random_uuid(), :t, :u, 'SELLING', now(), now())")
                .param("t", setup.tenantId())
                .param("u", setup.userId())
                .update();

        assertThat(submit(buy(setup, 10_000_000L)).result()).isEqualTo("LIQUIDATION_ACTIVE");
        assertThat(decisionRequests(setup.botId())).isZero();
    }

    @Test
    void aBotOfAnotherPersonIsNeverEvaluatedNorRecorded() {
        Setup setup = realBot("AUTO_FULL");
        TestAccounts.Seeded stranger = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        RealDecisionStep.Candidate foreign = RealDecisionStep.Candidate.buy(
                stranger.tenantId(), stranger.userId(), setup.botId(), UUID.randomUUID(), setup.mint(), 1_000L, "x");

        assertThat(submit(foreign).result()).isEqualTo("BOT_NOT_OWNED");
        assertThat(evaluations(setup.botId())).isZero();
        assertThat(decisionRequests(setup.botId())).isZero();
    }

    @Test
    void aRefusalOfTheBarrierRecordsNothingInTheExecution() {
        Setup setup = realBot("AUTO_FULL", 10_000_000L);

        RealDecisionStep.Outcome outcome = submit(buy(setup, 20_000_000L));

        assertThat(outcome.result()).isEqualTo("REJECTED");
        assertThat(evaluations(setup.botId())).isEqualTo(1);
        assertThat(decisionRequests(setup.botId())).isZero();
    }

    @Test
    void aBarrierWithoutItsLockLeavesNothingRecordedAndTheTransactionUsable() throws Exception {
        Setup setup = realBot("AUTO_FULL");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            holder.submit(() -> transactions.executeWithoutResult(status -> {
                // The key space of the U3 wallet lock ("U3RS", WalletLock.KEY_SPACE) and the same person.
                jdbc.sql("select pg_advisory_xact_lock(1429426771, hashtext(:u))")
                        .param("u", setup.userId().toString())
                        .query()
                        .listOfRows();
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            RealDecisionStep.Outcome outcome = submit(buy(setup, 10_000_000L));

            assertThat(outcome.result()).isEqualTo("UNAVAILABLE");
            assertThat(decisionRequests(setup.botId())).isZero();
        } finally {
            release.countDown();
            holder.shutdown();
        }
    }

    @Test
    void aSellOfTheBotIsRecordedWithoutAReservation() {
        Setup setup = realBot("AUTO_FULL");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);

        RealDecisionStep.Outcome outcome = submit(RealDecisionStep.Candidate.sell(
                setup.tenantId(), setup.userId(), setup.botId(), UUID.randomUUID(), setup.mint(), 5_000L, "alvo"));

        assertThat(outcome.result()).isEqualTo("RECORDED");
        assertThat(jdbc.sql("select approval_id from real_decision_request where decision_request_id = :id")
                        .param("id", outcome.decisionRequestId().orElseThrow())
                        .query(UUID.class)
                        .optional())
                .isEmpty();
    }

    @Test
    void recordDecisionNeedsTheTransactionOfTheCycleAndIsIdempotentByDecision() {
        Setup setup = realBot("AUTO_FULL");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        UUID decisionId = UUID.randomUUID();
        RealDecision decision = new RealDecision(
                setup.tenantId(),
                setup.userId(),
                setup.botId(),
                decisionId,
                Optional.empty(),
                RealSide.SELL,
                setup.mint(),
                null,
                5_000L,
                "alvo");

        assertThatThrownBy(() -> port.recordDecision(decision)).isInstanceOf(IllegalTransactionStateException.class);
        UUID first = transactions.execute(status -> port.recordDecision(decision));
        UUID again = transactions.execute(status -> port.recordDecision(decision));

        assertThat(again).isEqualTo(first);
        assertThat(decisionRequests(setup.botId())).isEqualTo(1);
    }

    @Test
    void aRealBotNeverWritesThePaperLedger() {
        Setup setup = realBot("AUTO_FULL");

        submit(buy(setup, 10_000_000L));
        consumer.runOnce();
        driver.runOnce();

        assertThat(jdbc.sql("select count(*) from paper_order where bot_id = :b")
                        .param("b", setup.botId())
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from ledger_entry where bot_id = :b")
                        .param("b", setup.botId())
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
