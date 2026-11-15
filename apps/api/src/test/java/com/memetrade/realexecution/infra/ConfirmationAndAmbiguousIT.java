package com.memetrade.realexecution.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.realexecution.AbstractRealExecutionIT;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 10 of the U4, written BEFORE the code (F5, F6, BR5.1, BR5.2, BR5.3; S5, S11 to S14; U4-NFR4.5): 60 s after the
 * send without a final answer the order is AMBIGUOUS and keeps its reservation; an ambiguous order is resolved only by
 * the network and never sent nor signed again; with the RPC down it stays ambiguous and the REAL of the person pauses.
 */
@Tag("u4-real-execution")
class ConfirmationAndAmbiguousIT extends AbstractRealExecutionIT {

    @Autowired
    private MeterRegistry meters;

    private UUID sentBuy(Setup setup) {
        UUID request = pendingBuy(setup, 50_000_000L);
        consumer.runOnce();
        UUID order = orderOf(request);
        driver.runOnce();
        assertThat(state(order)).isEqualTo("SENT");
        return order;
    }

    private UUID ambiguousBuy(Setup setup) {
        UUID order = sentBuy(setup);
        clock.advance(Duration.ofSeconds(60));
        confirmation.runOnce();
        assertThat(state(order)).isEqualTo("AMBIGUOUS");
        return order;
    }

    private String pauses(Setup setup) {
        return jdbc.sql("select array_to_string(paused_reasons, ',') from real_preferences where bot_wallet_id = :w")
                .param("w", setup.botWalletId())
                .query(String.class)
                .single();
    }

    @Test
    void s11SixtySecondsWithoutAFinalAnswerMakeTheOrderAmbiguousWithItsReservationKept() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = sentBuy(setup);

        clock.advance(Duration.ofSeconds(59));
        confirmation.runOnce();
        assertThat(state(order)).isEqualTo("SENT");
        clock.advance(Duration.ofSeconds(1));
        confirmation.runOnce();

        assertThat(state(order)).isEqualTo("AMBIGUOUS");
        assertThat(entries(order)).containsEntry("RESERVE", 50_000_000L).doesNotContainKey("RESERVE_RELEASE");
    }

    @Test
    void s5ARejectionForSlippageFailsTheOrderSlippageExceededAndReleasesTheReservation() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = sentBuy(setup);
        chain.fail(txSignature(order), true);

        confirmation.runOnce();

        assertThat(state(order)).isEqualTo("FAILED");
        assertThat(failureCode(order)).isEqualTo("SLIPPAGE_EXCEEDED");
        assertThat(entries(order)).containsEntry("RESERVE_RELEASE", 50_000_000L);
    }

    @Test
    void anyOtherRejectionOfTheNetworkFailsTheOrderChainRejected() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = sentBuy(setup);
        chain.fail(txSignature(order), false);

        confirmation.runOnce();

        assertThat(failureCode(order)).isEqualTo("CHAIN_REJECTED");
    }

    @Test
    void s24ASentOrderAbsentPastItsBlockhashFailsBlockhashExpired() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = sentBuy(setup);
        // The network never kept it: NOT_FOUND, and the height is past the last valid height of its blockhash.
        chain.reset();
        chain.height.set(5_000);

        confirmation.runOnce();

        assertThat(state(order)).isEqualTo("FAILED");
        assertThat(failureCode(order)).isEqualTo("BLOCKHASH_EXPIRED");
        assertThat(entries(order)).containsEntry("RESERVE_RELEASE", 50_000_000L);
    }

    @Test
    void s12AnAmbiguousOrderFinalizedOnTheNetworkIsConfirmed() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = ambiguousBuy(setup);
        chain.finalize(txSignature(order));

        ambiguous.runOnce();

        assertThat(state(order)).isEqualTo("CONFIRMED");
        assertThat(entries(order)).containsEntry("RESERVE_RELEASE", 50_000_000L);
    }

    @Test
    void s12AnAmbiguousOrderAbsentPastItsBlockhashFails() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = ambiguousBuy(setup);
        chain.reset();
        chain.height.set(5_000);

        ambiguous.runOnce();

        assertThat(state(order)).isEqualTo("FAILED");
        assertThat(failureCode(order)).isEqualTo("BLOCKHASH_EXPIRED");
    }

    @Test
    void s13WithTheRpcDownTheOrderStaysAmbiguousAndTheRealOfThePersonPauses() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = ambiguousBuy(setup);
        double before = meters.counter("real_upstream_pauses").count();
        chain.down = true;

        ambiguous.runOnce();

        assertThat(state(order)).isEqualTo("AMBIGUOUS");
        assertThat(pauses(setup)).contains("UPSTREAM");
        assertThat(meters.counter("real_upstream_pauses").count()).isEqualTo(before + 1);
        assertThat(entries(order)).doesNotContainKey("RESERVE_RELEASE");
    }

    @Test
    void s14AnAmbiguousOrderIsNeverSentNorSignedAgain() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = ambiguousBuy(setup);
        int sends = chain.totalSends();
        int signatures = signer.swapCalls.get();

        ambiguous.runOnce();
        confirmation.runOnce();
        driver.runOnce();
        clock.advance(Duration.ofMinutes(5));
        ambiguous.runOnce();

        assertThat(state(order)).isEqualTo("AMBIGUOUS");
        assertThat(chain.totalSends()).isEqualTo(sends);
        assertThat(signer.swapCalls.get()).isEqualTo(signatures);
    }

    @Test
    void theAmbiguousJobRefreshesTheGaugesOfTheWorker() {
        Setup setup = realBot("AUTO_FULL");
        ambiguousBuy(setup);

        ambiguous.runOnce();

        assertThat(meters.get("real_orders_ambiguous_open").gauge().value()).isGreaterThanOrEqualTo(1);
        assertThat(meters.get("real_orders_ambiguous_oldest_seconds").gauge().value())
                .isGreaterThanOrEqualTo(0);
        assertThat(meters.find("real_execution_job_runs")
                        .tag("job", "ambiguous")
                        .counter())
                .isNotNull();
    }
}
