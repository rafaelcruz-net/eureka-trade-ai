package com.memetrade.realexecution.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.botwallet.api.RealChain;
import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealPauses;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.realexecution.FakeCrashPoints;
import com.memetrade.realexecution.api.CancelScope;
import com.memetrade.realexecution.api.RealFailureCode;
import java.time.Duration;
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
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 8 of the U4, written BEFORE the code (F3, F5, BR3.1 to BR3.3, BR4.1, BR5.1, BR9.1; S1, S2, S4, S24; U4-NFR4.1
 * to U4-NFR4.4): the driver quotes, records the message BEFORE asking a signature, signs once, records SENT and the
 * attempt BEFORE the network, and resumes after a crash at any step from the database alone: the same message, the
 * same signingRequestId, the same transaction; never a second transaction.
 */
@Tag("u4-real-execution")
class OrderDriverIT extends AbstractRealExecutionIT {

    @Autowired
    private RealPauses pauses;

    private UUID proposedBuy(Setup setup, long lamports) {
        UUID request = pendingBuy(setup, lamports);
        consumer.runOnce();
        return orderOf(request);
    }

    private String attempts(UUID order) {
        return String.join(
                ",",
                jdbc.sql("select outcome from real_order_attempt where real_order_id = :id" + " order by sent_at")
                        .param("id", order)
                        .query(String.class)
                        .list());
    }

    private void stealLease(UUID order) {
        jdbc.sql("update real_order set lease_owner = 'other-worker', lease_until = now() + interval '1 minute'"
                        + " where real_order_id = :id")
                .param("id", order)
                .update();
    }

    /**
     * Review (Minor 3), before the code: the lease of the driver guards the SIGNED step too. When another worker took
     * the order while the TxSigner signed (the lease of this one expired), this driver records nothing and sends nothing;
     * the order stays QUOTED for the holder of the lease.
     */
    @Test
    void aDriverThatLostTheLeaseWhileSigningNeitherRecordsSignedNorSends() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = proposedBuy(setup, 50_000_000L);
        signer.whileSigning = () -> stealLease(order);

        driver.runOnce();

        assertThat(state(order)).isEqualTo("QUOTED");
        assertThat(chain.totalSends()).isZero();
        assertThat(jdbc.sql("select lease_owner from real_order where real_order_id = :id")
                        .param("id", order)
                        .query(String.class)
                        .single())
                .isEqualTo("other-worker");
    }

    /** Review (Minor 3): the same for the SENT step; the loser never records the send nor calls the network. */
    @Test
    void aDriverThatLostTheLeaseAfterSignedNeitherRecordsSentNorSends() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = proposedBuy(setup, 50_000_000L);
        crashes.onReach("signed", () -> stealLease(order));

        driver.runOnce();

        assertThat(state(order)).isEqualTo("SIGNED");
        assertThat(chain.totalSends()).isZero();
        assertThat(jdbc.sql("select count(*) from real_order_attempt where real_order_id = :id")
                        .param("id", order)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** Review (Minor 3): a failure step of the loser (TxSigner refusal) does not fail an order another worker holds. */
    @Test
    void aDriverThatLostTheLeaseDoesNotFailTheOrderOnARefusal() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = proposedBuy(setup, 50_000_000L);
        signer.swapRefusal = "CAP_EXCEEDED";
        signer.whileSigning = () -> stealLease(order);

        driver.runOnce();

        assertThat(state(order)).isEqualTo("QUOTED");
        assertThat(entries(order)).doesNotContainKey("RESERVE_RELEASE");
    }

    /**
     * Regression found by the informative measurement (Step 17): a second order with the same quote and the same blockhash
     * as an order already signed gives byte for byte the same transaction (Ed25519 is deterministic). It is never
     * recorded nor sent (the network would take it for the first one): the order fails BLOCKHASH_EXPIRED, its reserve
     * is released and the driver goes on, instead of stopping on the unique signature forever.
     */
    @Test
    void anIdenticalTransactionOfAnotherOrderIsNeverSentAndTheOrderFailsBlockhashExpired() {
        chain.sameBlockhash = true;
        Setup setup = realBot("AUTO_FULL");
        UUID first = proposedBuy(setup, 50_000_000L);
        driver.runOnce();
        chain.effect(txSignature(first), new RealChain.SwapEffect(-50_008_457L, 1_000_000L, 8_457L, true));
        chain.finalize(txSignature(first));
        confirmation.runOnce();
        assertThat(state(first)).isEqualTo("CONFIRMED");
        int sends = chain.totalSends();

        UUID second = proposedBuy(setup, 50_000_000L);
        driver.runOnce();

        assertThat(state(second)).isEqualTo("FAILED");
        assertThat(failureCode(second)).isEqualTo("BLOCKHASH_EXPIRED");
        assertThat(chain.totalSends()).isEqualTo(sends);
        assertThat(entries(second)).containsEntry("RESERVE_RELEASE", 50_000_000L);
    }

    @Test
    void s1AnAutoFullBuyIsQuotedSignedAndSentOnceWithTheMessageRecordedFirst() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = proposedBuy(setup, 50_000_000L);

        driver.runOnce();

        assertThat(state(order)).isEqualTo("SENT");
        var row = jdbc.sql(
                        "select message_digest, expected_out, min_out, fee_lamports, fee_payer, signing_requested_at,"
                                + " last_valid_block_height from real_order where real_order_id = :id")
                .param("id", order)
                .query()
                .singleRow();
        assertThat(row.get("message_digest")).isNotNull();
        assertThat(row.get("expected_out")).isEqualTo(1_000_000L);
        assertThat(row.get("min_out")).isEqualTo(980_000L);
        assertThat(row.get("fee_lamports")).isEqualTo(8_457L);
        assertThat(row.get("fee_payer")).isEqualTo("BOT_WALLET");
        assertThat(row.get("signing_requested_at")).isNotNull();
        assertThat(chain.sends(txSignature(order))).isEqualTo(1);
        assertThat(attempts(order)).isEqualTo("ACCEPTED");
        assertThat(signer.swapSignatures.get()).isEqualTo(1);
    }

    @Test
    void s4ACrashBetweenTheSignatureAndSignedSignsAgainWithTheSameRequestAndSendsOneTransaction() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = proposedBuy(setup, 50_000_000L);
        crashes.crashAt("signature-received");

        assertThatThrownByOrSwallowed(() -> driver.runOnce());
        assertThat(state(order)).isEqualTo("QUOTED");
        leaseExpired(order);
        driver.runOnce();

        assertThat(state(order)).isEqualTo("SENT");
        assertThat(signer.swapSignatures.get())
                .as("the TxSigner returned the same signature")
                .isEqualTo(1);
        assertThat(signer.swapCalls.get()).isEqualTo(2);
        assertThat(chain.totalSends()).isEqualTo(1);
    }

    @Test
    void aCrashAfterSignedSendsTheRecordedTransactionWithoutAskingAgain() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = proposedBuy(setup, 50_000_000L);
        crashes.crashAt("signed");

        assertThatThrownByOrSwallowed(() -> driver.runOnce());
        assertThat(state(order)).isEqualTo("SIGNED");
        leaseExpired(order);
        driver.runOnce();

        assertThat(state(order)).isEqualTo("SENT");
        assertThat(signer.swapCalls.get()).isEqualTo(1);
        assertThat(chain.sends(txSignature(order))).isEqualTo(1);
    }

    @Test
    void s24ACrashAfterSentIsRecordedAndBeforeTheNetworkResendsTheSameTransaction() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = proposedBuy(setup, 50_000_000L);
        crashes.crashAt("sent-recorded");

        assertThatThrownByOrSwallowed(() -> driver.runOnce());
        assertThat(state(order)).isEqualTo("SENT");
        assertThat(chain.totalSends()).isZero();
        assertThat(attempts(order)).isEqualTo("PENDING");
        leaseExpired(order);
        confirmation.runOnce();

        assertThat(chain.sends(txSignature(order))).isEqualTo(1);
        chain.finalize(txSignature(order));
        confirmation.runOnce();
        assertThat(state(order)).isEqualTo("CONFIRMED");
        assertThat(chain.totalSends()).isEqualTo(1);
    }

    @Test
    void aSendAcceptedByTheNetworkWithItsAnswerLostIsConfirmedWithoutASecondTransaction() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = proposedBuy(setup, 50_000_000L);
        chain.loseSendAnswer = true;

        driver.runOnce();
        chain.loseSendAnswer = false;
        assertThat(state(order)).isEqualTo("SENT");
        chain.finalize(txSignature(order));
        leaseExpired(order);
        confirmation.runOnce();

        assertThat(state(order)).isEqualTo("CONFIRMED");
        assertThat(chain.sends(txSignature(order))).isEqualTo(1);
    }

    @Test
    void aCancelFromOutsideWinsAndTheSignatureIsDiscardedWithoutASend() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = proposedBuy(setup, 50_000_000L);
        router.whileAnswering = () -> transactions.executeWithoutResult(status ->
                port.cancelUnsent(CancelScope.user(setup.tenantId(), setup.userId()), RealFailureCode.KILL_SWITCH));

        driver.runOnce();

        assertThat(state(order)).isEqualTo("CANCELLED");
        assertThat(failureCode(order)).isEqualTo("KILL_SWITCH");
        assertThat(chain.totalSends()).isZero();
        assertThat(entries(order)).containsEntry("RESERVE", 50_000_000L).containsEntry("RESERVE_RELEASE", 50_000_000L);
    }

    @Test
    void twoDriversAtOnceHandleEachOrderOnce() throws Exception {
        Setup first = realBot("AUTO_FULL");
        Setup second = realBot("AUTO_FULL");
        UUID a = proposedBuy(first, 10_000_000L);
        UUID b = proposedBuy(second, 10_000_000L);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> run = () -> {
                start.await(5, TimeUnit.SECONDS);
                return driver.runOnce();
            };
            List<Future<Integer>> runs = List.of(pool.submit(run), pool.submit(run));
            start.countDown();
            for (Future<Integer> future : runs) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        assertThat(state(a)).isEqualTo("SENT");
        assertThat(state(b)).isEqualTo("SENT");
        assertThat(chain.totalSends()).isEqualTo(2);
        assertThat(signer.swapSignatures.get()).isEqualTo(2);
    }

    @Test
    void s2AQuoteOlderThanTenSecondsIsQuotedOnceMoreAndAgainFailsQuoteExpired() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = proposedBuy(setup, 50_000_000L);
        router.whileAnswering = () -> clock.advance(Duration.ofSeconds(11));

        driver.runOnce();

        assertThat(state(order)).isEqualTo("FAILED");
        assertThat(failureCode(order)).isEqualTo("QUOTE_EXPIRED");
        assertThat(router.quotes.get()).isEqualTo(2);
        assertThat(signer.swapCalls.get()).isZero();
        assertThat(entries(order)).containsEntry("RESERVE_RELEASE", 50_000_000L);
    }

    @Test
    void aStaleQuoteThatIsFreshAfterTheRequoteIsSigned() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = proposedBuy(setup, 50_000_000L);
        router.whileAnswering = () -> {
            clock.advance(Duration.ofSeconds(11));
            router.whileAnswering = () -> {};
        };

        driver.runOnce();

        assertThat(state(order)).isEqualTo("SENT");
        assertThat(jdbc.sql("select requoted from real_order where real_order_id = :id")
                        .param("id", order)
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void theJupiterDownFailsTheOrderAndReleasesTheReservation() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = proposedBuy(setup, 50_000_000L);
        router.down = true;

        driver.runOnce();

        assertThat(state(order)).isEqualTo("FAILED");
        assertThat(failureCode(order)).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(entries(order)).containsEntry("RESERVE_RELEASE", 50_000_000L);
    }

    @Test
    void aRefusalOfTheTxSignerFailsTheOrderSigningRefused() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = proposedBuy(setup, 50_000_000L);
        signer.swapRefusal = "DESTINATION_NOT_ALLOWED";

        driver.runOnce();

        assertThat(state(order)).isEqualTo("FAILED");
        assertThat(failureCode(order)).isEqualTo("SIGNING_REFUSED");
        assertThat(chain.totalSends()).isZero();
    }

    @Test
    void theKillSwitchBetweenSigningAndSendingCancelsTheSignedOrder() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = proposedBuy(setup, 50_000_000L);
        crashes.crashAt("signed");
        assertThatThrownByOrSwallowed(() -> driver.runOnce());
        transactions.executeWithoutResult(
                status -> pauses.pauseUser(setup.tenantId(), setup.userId(), RealPauseReason.KILL_SWITCH));
        leaseExpired(order);

        driver.runOnce();

        assertThat(state(order)).isEqualTo("CANCELLED");
        assertThat(failureCode(order)).isEqualTo("KILL_SWITCH");
        assertThat(chain.totalSends()).isZero();
        assertThat(entries(order)).containsEntry("RESERVE_RELEASE", 50_000_000L);
        // Regression (Step 17): the cancel before the send runs in one transaction with its audit event, which the
        // publisher requires (C9); before the fix the state was written alone and the event was lost.
        assertThat(cancelledEvents(order)).isEqualTo(1);
    }

    private long cancelledEvents(UUID order) {
        return jdbc.sql("select count(*) from audit_outbox where payload->>'action' = 'REAL_ORDER_STATE_CHANGED'"
                        + " and payload::text like :target and payload::text like '%CANCELLED%'")
                .param("target", "%real-order:" + order + "%")
                .query(Long.class)
                .single();
    }

    @Test
    void aRouteThatNeverFitsOnePacketFailsRouteTooLarge() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = proposedBuy(setup, 50_000_000L);
        router.tooLarge = 2;

        driver.runOnce();

        assertThat(state(order)).isEqualTo("FAILED");
        assertThat(failureCode(order)).isEqualTo("ROUTE_TOO_LARGE");
        assertThat(router.quotes.get()).isEqualTo(2);
    }

    @Test
    void aSignedModeOrderIsQuotedWithTheMainWalletAsFeePayerAndWaitsForTheSession() {
        Setup setup = realBot("SIGNED");
        UUID order = proposedBuy(setup, 50_000_000L);

        driver.runOnce();

        var row = jdbc.sql("select state, fee_payer, extract(epoch from signing_deadline - quote_at)::int || ' s' as"
                        + " window, fee_lamports from real_order where real_order_id = :id")
                .param("id", order)
                .query()
                .singleRow();
        assertThat(row.get("state")).isEqualTo("QUOTED");
        assertThat(row.get("fee_payer")).isEqualTo("MAIN_WALLET");
        assertThat(String.valueOf(row.get("window"))).isEqualTo("8 s");
        assertThat(row.get("fee_lamports")).isEqualTo(13_457L);
        assertThat(signer.swapCalls.get()).isZero();
    }

    /** A simulated crash propagates out of the job, or is logged by it; either way the step before it is committed. */
    private static void assertThatThrownByOrSwallowed(Runnable run) {
        try {
            run.run();
        } catch (FakeCrashPoints.SimulatedCrash crashed) {
            assertThatThrownBy(() -> {
                        throw crashed;
                    })
                    .isInstanceOf(FakeCrashPoints.SimulatedCrash.class);
        }
    }
}
