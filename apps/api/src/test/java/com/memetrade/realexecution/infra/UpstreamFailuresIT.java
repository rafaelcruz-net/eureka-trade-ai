package com.memetrade.realexecution.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.api.RealExecutionWallet;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 10 of the U4, written BEFORE the code (F9, BR9.1; S21; U4-NFR4.6): a technical failure of a provider before the
 * signature fails the order and releases its reservation; the third failure in a row of the same provider for the same
 * person pauses the REAL of that person (UPSTREAM); a success resets the count; the pause leaves when every provider is
 * healthy for 5 minutes and the person has no AMBIGUOUS order. The withdrawal never depends on any of this.
 */
@Tag("u4-real-execution")
class UpstreamFailuresIT extends AbstractRealExecutionIT {

    @Autowired
    private RealExecutionWallet wallets;

    @Autowired
    private com.memetrade.botwallet.api.RealPauses pauses;

    private UUID proposed(Setup setup) {
        UUID request = pendingBuy(setup, 10_000_000L);
        consumer.runOnce();
        return orderOf(request);
    }

    private boolean pausedUpstream(Setup setup) {
        return jdbc.sql("select 'UPSTREAM' = any(paused_reasons) from real_preferences where bot_wallet_id = :w")
                .param("w", setup.botWalletId())
                .query(Boolean.class)
                .single();
    }

    private void failOnce(Setup setup) {
        UUID order = proposed(setup);
        driver.runOnce();
        assertThat(failureCode(order)).isEqualTo("UPSTREAM_UNAVAILABLE");
    }

    /**
     * Review (Major 1): a route the Jupiter refuses (any 4xx but 429) fails only that order, NO_ROUTE, with the reserve
     * released; it is not an outage, so three in a row neither count for the Jupiter nor pause the person.
     */
    @Test
    void aRefusedRouteFailsTheOrderNoRouteAndNeverCountsNorPauses() {
        Setup setup = realBot("AUTO_FULL");
        router.noRoute = true;

        for (int i = 0; i < 3; i++) {
            UUID order = proposed(setup);
            driver.runOnce();
            assertThat(state(order)).isEqualTo("FAILED");
            assertThat(failureCode(order)).isEqualTo("NO_ROUTE");
            assertThat(entries(order)).containsEntry("RESERVE_RELEASE", 10_000_000L);
        }

        assertThat(pausedUpstream(setup)).isFalse();
        assertThat(jdbc.sql("select coalesce(max(consecutive_failures), 0) from real_upstream_health"
                                + " where subject_id = :u and provider = 'JUPITER'")
                        .param("u", setup.userId())
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void s21TheThirdFailureInARowOfTheJupiterPausesTheRealOfThePersonAndTheWithdrawalGoesOn() {
        Setup setup = realBot("AUTO_FULL");
        router.down = true;

        failOnce(setup);
        failOnce(setup);
        assertThat(pausedUpstream(setup)).isFalse();
        failOnce(setup);

        assertThat(pausedUpstream(setup)).isTrue();
        assertThat(wallets.requestWithdrawalAll(
                        setup.tenantId(), setup.userId(), UUID.randomUUID().toString()))
                .as("the withdrawal never passes the UPSTREAM pause")
                .isPresent();
    }

    @Test
    void aSuccessOfTheSameProviderResetsTheCount() {
        Setup setup = realBot("AUTO_FULL");
        router.down = true;
        failOnce(setup);
        failOnce(setup);
        router.down = false;
        UUID ok = proposed(setup);
        driver.runOnce();
        assertThat(state(ok)).isEqualTo("SENT");
        chain.finalize(txSignature(ok));
        confirmation.runOnce();
        router.down = true;

        failOnce(setup);
        failOnce(setup);

        assertThat(pausedUpstream(setup)).isFalse();
    }

    @Test
    void theTxSignerDownFailsTheOrderAndCountsForTheTxSigner() {
        Setup setup = realBot("AUTO_FULL");
        signer.down = true;

        failOnce(setup);
        failOnce(setup);
        failOnce(setup);

        assertThat(pausedUpstream(setup)).isTrue();
        assertThat(jdbc.sql("select consecutive_failures from real_upstream_health where subject_id = :u"
                                + " and provider = 'TXSIGNER'")
                        .param("u", setup.userId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(3);
    }

    @Test
    void theUpstreamPauseLeavesAfterFiveHealthyMinutesWithoutAnAmbiguousOrder() {
        Setup setup = realBot("AUTO_FULL");
        router.down = true;
        failOnce(setup);
        failOnce(setup);
        failOnce(setup);
        router.down = false;

        health.runOnce();
        clock.advance(Duration.ofMinutes(4));
        health.runOnce();
        assertThat(pausedUpstream(setup)).as("healthy for 4 minutes only").isTrue();
        clock.advance(Duration.ofMinutes(1));
        health.runOnce();

        assertThat(pausedUpstream(setup)).isFalse();
    }

    @Test
    void theUpstreamPauseStaysWhileAProviderIsDownOrThePersonHasAnAmbiguousOrder() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = proposed(setup);
        driver.runOnce();
        clock.advance(Duration.ofSeconds(60));
        confirmation.runOnce();
        assertThat(state(order)).isEqualTo("AMBIGUOUS");
        transactions.executeWithoutResult(status -> pauses.pauseUser(
                setup.tenantId(), setup.userId(), com.memetrade.botwallet.api.RealPauseReason.UPSTREAM));

        health.runOnce();
        clock.advance(Duration.ofMinutes(6));
        health.runOnce();
        assertThat(pausedUpstream(setup))
                .as("an ambiguous order keeps the pause")
                .isTrue();

        chain.finalize(txSignature(order));
        ambiguous.runOnce();
        signer.down = true;
        health.runOnce();
        clock.advance(Duration.ofMinutes(6));
        health.runOnce();
        assertThat(pausedUpstream(setup)).as("the TxSigner is down").isTrue();
    }
}
