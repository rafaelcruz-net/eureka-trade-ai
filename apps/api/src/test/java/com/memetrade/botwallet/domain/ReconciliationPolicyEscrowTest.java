package com.memetrade.botwallet.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 5 of the U7 of 261113-trading-real-bot, written BEFORE the code (BR5.3b, S49, U7-NFR4.8): while a conditional
 * of the Jupiter is OPEN or CANCELLING the token of the position is in the custody account of the order. The
 * reconciliation adds the balance of each custody account, read on the network, to the balance of the mint in the bot
 * wallet; a custody that could not be read makes the reconciliation UNAVAILABLE (the network was not read), never
 * DIVERGENT.
 */
@Tag("u7-exits-and-fiscal")
class ReconciliationPolicyEscrowTest {

    private static final String MINT = "Mint1111111111111111111111111111111111111111";
    private static final String OTHER = "Mint2222222222222222222222222222222222222222";
    private static final long SOL = 1_000_000_000L;

    private static ReconciliationInputs settled(
            ChainSnapshot chain, LedgerSnapshot ledger, Optional<Map<String, Long>> escrows) {
        return new ReconciliationInputs(0, 0, Optional.of(new ChainReading(false, chain)), ledger, escrows);
    }

    /** S49: the whole position in the custody of an OPEN conditional is no divergence. */
    @Test
    void thePositionInTheCustodyOfAnOpenConditionalIsClean() {
        ReconciliationOutcome outcome = ReconciliationPolicy.decide(settled(
                new ChainSnapshot(700, 2 * SOL, Map.of()),
                new LedgerSnapshot(2 * SOL, Map.of(MINT, 35_000L)),
                Optional.of(Map.of(MINT, 35_000L))));

        assertThat(outcome.result()).isEqualTo(ReconciliationResult.CLEAN);
        assertThat(outcome.tokenDiffs()).isEmpty();
    }

    /** Without the custody, the same wallet with the token gone from the bot wallet would be a divergence. */
    @Test
    void withoutTheCustodyTheMissingTokenIsADivergence() {
        ReconciliationOutcome outcome = ReconciliationPolicy.decide(settled(
                new ChainSnapshot(700, 2 * SOL, Map.of()),
                new LedgerSnapshot(2 * SOL, Map.of(MINT, 35_000L)),
                Optional.of(Map.of())));

        assertThat(outcome.result()).isEqualTo(ReconciliationResult.DIVERGENT);
        assertThat(outcome.tokenDiffs()).containsExactly(new TokenDiff(MINT, 0L, 35_000L));
    }

    /** U7-NFR4.8: a custody account that could not be read is UNAVAILABLE, never DIVERGENT. */
    @Test
    void aCustodyThatCouldNotBeReadIsUnavailable() {
        ReconciliationOutcome outcome = ReconciliationPolicy.decide(settled(
                new ChainSnapshot(700, 2 * SOL, Map.of()),
                new LedgerSnapshot(2 * SOL, Map.of(MINT, 35_000L)),
                Optional.empty()));

        assertThat(outcome.result()).isEqualTo(ReconciliationResult.UNAVAILABLE);
        assertThat(outcome.result().measured()).isFalse();
    }

    @Test
    void partOfTheMintInTheBotWalletAndPartInTheCustodyAddUp() {
        ReconciliationOutcome outcome = ReconciliationPolicy.decide(settled(
                new ChainSnapshot(700, SOL, Map.of(MINT, 10_000L, OTHER, 7L)),
                new LedgerSnapshot(SOL, Map.of(MINT, 35_000L, OTHER, 7L)),
                Optional.of(Map.of(MINT, 25_000L))));

        assertThat(outcome.result()).isEqualTo(ReconciliationResult.CLEAN);
    }

    @Test
    void moreInTheCustodyThanInTheLedgerIsADivergenceOfTheNetworkMinusTheLedger() {
        ReconciliationOutcome outcome = ReconciliationPolicy.decide(settled(
                new ChainSnapshot(700, SOL, Map.of()),
                new LedgerSnapshot(SOL, Map.of(MINT, 35_000L)),
                Optional.of(Map.of(MINT, 35_500L))));

        assertThat(outcome.result()).isEqualTo(ReconciliationResult.DIVERGENT);
        assertThat(outcome.tokenDiffs()).containsExactly(new TokenDiff(MINT, 35_500L, 35_000L));
    }

    /** Something in transit is still DEFERRED first: the custody is not even considered. */
    @Test
    void anOrderInTransitIsStillDeferredBeforeTheCustody() {
        ReconciliationOutcome outcome = ReconciliationPolicy.decide(new ReconciliationInputs(
                1, 0, Optional.empty(), new LedgerSnapshot(SOL, Map.of(MINT, 1L)), Optional.empty()));

        assertThat(outcome.result()).isEqualTo(ReconciliationResult.DEFERRED);
    }

    /** The simulated adapter keeps the token in the bot wallet: no custody, the reconciliation stays clean. */
    @Test
    void aSimulatedConditionalKeepsTheTokenInTheBotWalletAndStaysClean() {
        ReconciliationOutcome outcome = ReconciliationPolicy.decide(new ReconciliationInputs(
                0,
                0,
                Optional.of(new ChainReading(false, new ChainSnapshot(9, SOL, Map.of(MINT, 35_000L)))),
                new LedgerSnapshot(SOL, Map.of(MINT, 35_000L))));

        assertThat(outcome.result()).isEqualTo(ReconciliationResult.CLEAN);
    }
}
