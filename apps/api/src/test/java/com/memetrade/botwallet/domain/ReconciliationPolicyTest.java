package com.memetrade.botwallet.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 6 of the U5, written BEFORE the code (BR2.1, Q1, S10 to S12c, U5-NFR2.1, U5-NFR4.3): the pure reconciliation of
 * one bot wallet. Only a settled wallet is compared (no order or withdrawal in transit, no entry of the network not yet
 * read by the deposit scan); then the SOL of its system account and every mint, read at the last finalized slot, against
 * the REAL ledger with ZERO tolerance (the ledger already holds the real fees, the rent and the wrapped SOL). A network
 * that did not answer is UNAVAILABLE, never a divergence.
 */
@Tag("u5-real-operations")
class ReconciliationPolicyTest {

    private static final String MINT = "Mint1111111111111111111111111111111111111111";
    private static final String OTHER = "Mint2222222222222222222222222222222222222222";
    private static final long SOL = 1_000_000_000L;

    private static ReconciliationInputs settled(ChainSnapshot chain, LedgerSnapshot ledger) {
        return new ReconciliationInputs(0, 0, Optional.of(new ChainReading(false, chain)), ledger);
    }

    /** S10: after a confirmed order the chain and the ledger agree: CLEAN. */
    @Test
    void equalBalancesAreClean() {
        ReconciliationOutcome outcome = ReconciliationPolicy.decide(settled(
                new ChainSnapshot(500, 2 * SOL, Map.of(MINT, 35_000L)),
                new LedgerSnapshot(2 * SOL, Map.of(MINT, 35_000L))));

        assertThat(outcome.result()).isEqualTo(ReconciliationResult.CLEAN);
        assertThat(outcome.slot()).isEqualTo(500L);
        assertThat(outcome.chainLamports()).isEqualTo(2 * SOL);
        assertThat(outcome.ledgerLamports()).isEqualTo(2 * SOL);
        assertThat(outcome.lamportsDiff()).isZero();
        assertThat(outcome.tokenDiffs()).isEmpty();
    }

    /** S11: 1.000 lamports more on the network: DIVERGENT, network minus ledger. */
    @Test
    void oneThousandLamportsMoreOnTheNetworkIsDivergent() {
        ReconciliationOutcome outcome = ReconciliationPolicy.decide(
                settled(new ChainSnapshot(501, SOL + 1_000, Map.of()), new LedgerSnapshot(SOL, Map.of())));

        assertThat(outcome.result()).isEqualTo(ReconciliationResult.DIVERGENT);
        assertThat(outcome.lamportsDiff()).isEqualTo(1_000L);
    }

    @Test
    void theToleranceIsZeroEvenForOneLamportLess() {
        ReconciliationOutcome outcome = ReconciliationPolicy.decide(
                settled(new ChainSnapshot(1, SOL - 1, Map.of()), new LedgerSnapshot(SOL, Map.of())));

        assertThat(outcome.result()).isEqualTo(ReconciliationResult.DIVERGENT);
        assertThat(outcome.lamportsDiff()).isEqualTo(-1L);
    }

    @Test
    void aTokenOnlyOnTheNetworkIsADivergenceOfThatMintWithTheNetworkAndTheLedger() {
        ReconciliationOutcome outcome = ReconciliationPolicy.decide(
                settled(new ChainSnapshot(9, SOL, Map.of(MINT, 750L)), new LedgerSnapshot(SOL, Map.of())));

        assertThat(outcome.result()).isEqualTo(ReconciliationResult.DIVERGENT);
        assertThat(outcome.lamportsDiff()).isZero();
        assertThat(outcome.tokenDiffs()).containsExactly(new TokenDiff(MINT, 750L, 0L));
        assertThat(outcome.tokenDiffs().getFirst().diff()).isEqualTo(750L);
    }

    @Test
    void aTokenOnlyInTheLedgerAndAnEmptyAccountOnTheNetworkCountAsZero() {
        ReconciliationOutcome outcome = ReconciliationPolicy.decide(settled(
                new ChainSnapshot(9, SOL, Map.of(OTHER, 0L)), new LedgerSnapshot(SOL, Map.of(MINT, 10L, OTHER, 0L))));

        assertThat(outcome.tokenDiffs()).containsExactly(new TokenDiff(MINT, 0L, 10L));
        assertThat(outcome.tokenDiffs().getFirst().diff()).isEqualTo(-10L);
    }

    @Test
    void theTokenDiffsComeInTheOrderOfTheMint() {
        ReconciliationOutcome outcome = ReconciliationPolicy.decide(
                settled(new ChainSnapshot(9, SOL, Map.of(OTHER, 5L, MINT, 1L)), new LedgerSnapshot(SOL, Map.of())));

        assertThat(outcome.tokenDiffs()).extracting(TokenDiff::mint).containsExactly(MINT, OTHER);
    }

    /** S12: an order SENT with 0.05 SOL reserved: DEFERRED, no divergence until it ends. */
    @Test
    void anOrderInTransitDefersWithoutReadingTheNetwork() {
        ReconciliationOutcome outcome = ReconciliationPolicy.decide(
                new ReconciliationInputs(1, 0, Optional.empty(), new LedgerSnapshot(SOL, Map.of())));

        assertThat(outcome.result()).isEqualTo(ReconciliationResult.DEFERRED);
        assertThat(outcome.inTransit()).isEqualTo(1);
        assertThat(outcome.slot()).isNull();
    }

    @Test
    void aWithdrawalInTransitDefersToo() {
        ReconciliationOutcome outcome = ReconciliationPolicy.decide(new ReconciliationInputs(
                0,
                1,
                Optional.of(new ChainReading(false, new ChainSnapshot(1, 0, Map.of()))),
                new LedgerSnapshot(SOL, Map.of())));

        assertThat(outcome.result()).isEqualTo(ReconciliationResult.DEFERRED);
    }

    /** S12b: a finalized deposit not yet read by the deposit scan: DEFERRED; once read, CLEAN. */
    @Test
    void anEntryOfTheNetworkNotYetReadDefersAndOnceReadIsClean() {
        ChainSnapshot chain = new ChainSnapshot(7, 2 * SOL, Map.of());

        ReconciliationOutcome unread = ReconciliationPolicy.decide(new ReconciliationInputs(
                0, 0, Optional.of(new ChainReading(true, chain)), new LedgerSnapshot(SOL, Map.of())));
        ReconciliationOutcome read = ReconciliationPolicy.decide(settled(chain, new LedgerSnapshot(2 * SOL, Map.of())));

        assertThat(unread.result()).isEqualTo(ReconciliationResult.DEFERRED);
        assertThat(read.result()).isEqualTo(ReconciliationResult.CLEAN);
    }

    /** U5-NFR4.3: the network did not answer: UNAVAILABLE, no divergence and no pause. */
    @Test
    void aNetworkThatDidNotAnswerIsUnavailable() {
        ReconciliationOutcome outcome = ReconciliationPolicy.decide(
                new ReconciliationInputs(0, 0, Optional.empty(), new LedgerSnapshot(SOL, Map.of())));

        assertThat(outcome.result()).isEqualTo(ReconciliationResult.UNAVAILABLE);
        assertThat(outcome.tokenDiffs()).isEmpty();
    }

    /** S12c: in the SIGNED mode the fee is paid by the main wallet: neither the bot wallet nor its ledger changes. */
    @Test
    void theFeePaidByTheMainWalletTouchesNeitherSideAndIsClean() {
        long afterSignedSwapBot = 2 * SOL - 50_000_000L;
        ReconciliationOutcome outcome = ReconciliationPolicy.decide(settled(
                new ChainSnapshot(42, afterSignedSwapBot, Map.of(MINT, 35_728_672L)),
                new LedgerSnapshot(afterSignedSwapBot, Map.of(MINT, 35_728_672L))));

        assertThat(outcome.result()).isEqualTo(ReconciliationResult.CLEAN);
    }

    @Test
    void onlyTheResultsThatComparedCarryTheMeasure() {
        assertThat(ReconciliationResult.CLEAN.measured()).isTrue();
        assertThat(ReconciliationResult.DIVERGENT.measured()).isTrue();
        assertThat(ReconciliationResult.DEFERRED.measured()).isFalse();
        assertThat(ReconciliationResult.UNAVAILABLE.measured()).isFalse();
    }
}
