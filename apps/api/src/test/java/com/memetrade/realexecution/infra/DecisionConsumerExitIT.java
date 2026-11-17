package com.memetrade.realexecution.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.application.RealDecisionStep;
import com.memetrade.realexits.AbstractRealExitsIT;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 5 of the U7 of 261113-trading-real-bot, written BEFORE the code (BR4.3, S19, S37, U7-NFR4.3, logical-components.md
 * "Desfecho novo na U4, DEFERRED"): the consumer of the decision queue asks the guard before a sale. WAIT defers the
 * decision 30 s (attempts + 1) and, after 10 attempts, discards it with EXIT_CANCEL_TIMEOUT; READY reads the position
 * again from the REAL ledger and a position at zero discards it with NOTHING_TO_SELL, so a position is never sold twice.
 */
@Tag("u7-exits-and-fiscal")
class DecisionConsumerExitIT extends AbstractRealExitsIT {

    private UUID sell(Setup setup, long units) {
        return transactions
                .execute(status -> decisionStep.submit(RealDecisionStep.Candidate.sell(
                        setup.tenantId(),
                        setup.userId(),
                        setup.botId(),
                        UUID.randomUUID(),
                        setup.mint(),
                        units,
                        "alvo")))
                .decisionRequestId()
                .orElseThrow();
    }

    private String decisionState(UUID request) {
        return jdbc.sql("select state || ':' || coalesce(discard_reason, '-') || ':' || attempts from"
                        + " real_decision_request where decision_request_id = :id")
                .param("id", request)
                .query(String.class)
                .single();
    }

    private long ordersOf(UUID request) {
        return jdbc.sql("select count(*) from real_order where idempotency_key = :key")
                .param("key", request.toString())
                .query(Long.class)
                .single();
    }

    /** S19: an OPEN conditional on the position defers the sale and is marked CANCELLING. */
    @Test
    void aSaleOfAPositionWithAnOpenConditionalIsDeferredThirtySeconds() {
        Setup setup = realBot("AUTO_EXITS");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        UUID conditional = conditional(setup, "OPEN", 5_000L);
        UUID request = sell(setup, 5_000L);

        consumer.runOnce();

        assertThat(decisionState(request)).isEqualTo("PENDING:-:1");
        assertThat(ordersOf(request)).isZero();
        assertThat(conditionalState(conditional)).isEqualTo("CANCELLING");
        assertThat(jdbc.sql("select not_before from real_decision_request where decision_request_id = :id")
                        .param("id", request)
                        .query(Timestamp.class)
                        .single()
                        .toInstant())
                .isEqualTo(clock.instant().plus(Duration.ofSeconds(30)));
    }

    /** A deferred decision is not read again before its time. */
    @Test
    void aDeferredDecisionIsIgnoredUntilItsTime() {
        Setup setup = realBot("AUTO_EXITS");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        conditional(setup, "OPEN", 5_000L);
        UUID request = sell(setup, 5_000L);
        consumer.runOnce();

        clock.advance(Duration.ofSeconds(10));
        consumer.runOnce();

        assertThat(decisionState(request)).isEqualTo("PENDING:-:1");
    }

    /** logical-components.md: after 10 attempts (about 5 minutes) the decision is DISCARDED EXIT_CANCEL_TIMEOUT. */
    @Test
    void afterTenAttemptsTheDecisionIsDiscardedWithExitCancelTimeout() {
        Setup setup = realBot("AUTO_EXITS");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        conditional(setup, "OPEN", 5_000L);
        UUID request = sell(setup, 5_000L);

        for (int round = 0; round < 10; round++) {
            consumer.runOnce();
            clock.advance(Duration.ofSeconds(30));
        }

        assertThat(decisionState(request)).isEqualTo("DISCARDED:EXIT_CANCEL_TIMEOUT:9");
        assertThat(ordersOf(request)).isZero();
    }

    /** S19: once the conditional is CANCELLED the deferred sale goes on as an order. */
    @Test
    void theSaleGoesOnAfterTheConditionalIsCancelled() {
        Setup setup = realBot("AUTO_EXITS");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        UUID conditional = conditional(setup, "OPEN", 5_000L);
        UUID request = sell(setup, 5_000L);
        consumer.runOnce();
        jdbc.sql("update real_conditional_order set state = 'CANCELLED', final_at = now() where"
                        + " conditional_order_id = :id")
                .param("id", conditional)
                .update();

        clock.advance(Duration.ofSeconds(30));
        consumer.runOnce();

        assertThat(decisionState(request)).isEqualTo("PROCESSED:-:1");
        assertThat(ordersOf(request)).isOne();
    }

    /** S37: the conditional filled first; the later sale reads the position at zero and sends nothing. */
    @Test
    void aSaleOfAPositionAlreadyFilledByTheConditionalEndsWithNothingToSell() {
        Setup setup = realBot("AUTO_EXITS");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        UUID request = sell(setup, 5_000L);
        String sold = "conditional:" + UUID.randomUUID();
        transactions.executeWithoutResult(status -> ledger.record(com.memetrade.ledger.api.RealEntry.swapFill(
                setup.tenantId(),
                setup.botWalletId(),
                com.memetrade.ledger.api.RealEntryKind.SWAP_FILL_OUT,
                1_200_000L,
                setup.mint(),
                5_000L,
                sold,
                sold + ":fill")));

        consumer.runOnce();

        assertThat(decisionState(request)).isEqualTo("DISCARDED:NOTHING_TO_SELL:0");
        assertThat(ordersOf(request)).isZero();
        assertThat(chain.totalSends()).isZero();
    }
}
