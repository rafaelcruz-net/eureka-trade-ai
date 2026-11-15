package com.memetrade.realexecution.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.realexecution.AbstractRealExecutionIT;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 8 of the U4, written BEFORE the code (F2, BR2.1, BR2.2, BR8.1, S30; U4-NFR4.1, U4-NFR4.7): the consumer turns a
 * PENDING decision into a PROPOSED order in one short transaction, under the lock of the person, with the hand-over of
 * the reservation of the barrier and the RESERVE of the ledger; a decision is consumed once even by two consumers.
 */
@Tag("u4-real-execution")
class DecisionConsumerIT extends AbstractRealExecutionIT {

    private String decisionState(UUID requestId) {
        return jdbc.sql("select state || coalesce(':' || discard_reason, '') from real_decision_request"
                        + " where decision_request_id = :id")
                .param("id", requestId)
                .query(String.class)
                .single();
    }

    private long orders(UUID botId) {
        return jdbc.sql("select count(*) from real_order where bot_id = :b")
                .param("b", botId)
                .query(Long.class)
                .single();
    }

    @Test
    void aPendingBuyBecomesAProposedOrderWithItsReserveAndTheReservationHandedOver() {
        Setup setup = realBot("AUTO_FULL");
        UUID request = pendingBuy(setup, 50_000_000L);

        assertThat(consumer.runOnce()).isEqualTo(1);

        UUID order = orderOf(request);
        assertThat(state(order)).isEqualTo("PROPOSED");
        assertThat(decisionState(request)).isEqualTo("PROCESSED");
        assertThat(entries(order)).containsEntry("RESERVE", 50_000_000L);
        assertThat(jdbc.sql("select r.state from real_risk_reservation r join real_decision_request d"
                                + " on d.approval_id = r.approval_id where d.decision_request_id = :id")
                        .param("id", request)
                        .query(String.class)
                        .single())
                .isEqualTo("HANDED_OVER");
        assertThat(jdbc.sql("select operation_mode || '/' || signing_mode || '/' || max_slippage_bps"
                                + " from real_order where real_order_id = :id")
                        .param("id", order)
                        .query(String.class)
                        .single())
                .isEqualTo("AUTO_FULL/AUTO_FULL/200");
    }

    @Test
    void theSameDecisionConsumedByTwoConsumersAtOnceGivesOneOrder() throws Exception {
        Setup setup = realBot("AUTO_FULL");
        UUID request = pendingBuy(setup, 10_000_000L);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> run = () -> {
                start.await(5, TimeUnit.SECONDS);
                return consumer.runOnce();
            };
            List<Future<Integer>> runs = List.of(pool.submit(run), pool.submit(run));
            start.countDown();
            for (Future<Integer> future : runs) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        assertThat(orders(setup.botId())).isEqualTo(1);
        assertThat(decisionState(request)).isEqualTo("PROCESSED");
        assertThat(entries(orderOf(request))).containsEntry("RESERVE", 10_000_000L);
    }

    @Test
    void anExpiredReservationDiscardsTheDecisionWithoutAnOrderNorAReserve() {
        Setup setup = realBot("AUTO_FULL");
        UUID request = pendingBuy(setup, 10_000_000L);
        clock.advance(java.time.Duration.ofMinutes(6));

        consumer.runOnce();

        assertThat(decisionState(request)).isEqualTo("DISCARDED:RESERVATION_EXPIRED");
        assertThat(orders(setup.botId())).isZero();
    }

    @Test
    void s3ASecondDecisionOfABotWithAnOrderInProgressIsDiscarded() {
        Setup setup = realBot("AUTO_FULL");
        consumer.runOnce();
        UUID first = pendingBuy(setup, 10_000_000L);
        UUID second = pendingBuy(setup, 10_000_000L);

        consumer.runOnce();

        assertThat(decisionState(first)).isEqualTo("PROCESSED");
        assertThat(decisionState(second)).isEqualTo("DISCARDED:ORDER_IN_PROGRESS");
        assertThat(orders(setup.botId())).isEqualTo(1);
    }

    @Test
    void s30AnActiveLiquidationDiscardsTheDecisionOfTheConsumer() {
        Setup setup = realBot("AUTO_FULL");
        UUID request = pendingBuy(setup, 10_000_000L);
        jdbc.sql("insert into real_liquidation (liquidation_id, tenant_id, user_id, state, created_at, updated_at)"
                        + " values (gen_random_uuid(), :t, :u, 'SELLING', now(), now())")
                .param("t", setup.tenantId())
                .param("u", setup.userId())
                .update();

        consumer.runOnce();

        assertThat(decisionState(request)).isEqualTo("DISCARDED:LIQUIDATION_ACTIVE");
        assertThat(orders(setup.botId())).isZero();
    }

    @Test
    void aBotThatStoppedBeingRealOrOfThePersonIsDiscarded() {
        Setup setup = realBot("AUTO_FULL");
        UUID request = pendingBuy(setup, 10_000_000L);
        jdbc.sql("update bot set state = 'STOPPED' where bot_id = :b")
                .param("b", setup.botId())
                .update();

        consumer.runOnce();

        assertThat(decisionState(request)).isEqualTo("DISCARDED:BOT_NOT_OWNED");
    }

    @Test
    void withoutTheLockOfThePersonTheDecisionStaysPendingForTheNextRound() throws Exception {
        Setup setup = realBot("AUTO_FULL");
        UUID request = pendingBuy(setup, 10_000_000L);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            holder.submit(() -> transactions.executeWithoutResult(status -> {
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

            consumer.runOnce();

            assertThat(decisionState(request)).isEqualTo("PENDING");
        } finally {
            release.countDown();
            holder.shutdown();
        }
        consumer.runOnce();
        assertThat(decisionState(request)).isEqualTo("PROCESSED");
    }

    @Test
    void aSellBecomesAnOrderWithoutAReserve() {
        Setup setup = realBot("AUTO_FULL");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        var outcome = transactions.execute(
                status -> decisionStep.submit(com.memetrade.bots.application.RealDecisionStep.Candidate.sell(
                        setup.tenantId(),
                        setup.userId(),
                        setup.botId(),
                        UUID.randomUUID(),
                        setup.mint(),
                        5_000L,
                        "alvo")));

        consumer.runOnce();

        UUID order = orderOf(outcome.decisionRequestId().orElseThrow());
        assertThat(state(order)).isEqualTo("PROPOSED");
        assertThat(entries(order)).isEmpty();
    }
}
