package com.memetrade.botwallet.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 6 of the U5, written BEFORE the code (BR2.3, S13, S14, U5-NFR4.6): a compensation is one item per divergent asset
 * (SOL in lamports, each mint in base units, with its sign) and each item must be EXACTLY the difference measured by the
 * last reconciliation of the divergence (network minus ledger), so the administrator never posts an arbitrary value.
 */
@Tag("u5-real-operations")
class CompensationRulesTest {

    private static final String MINT = "Mint1111111111111111111111111111111111111111";

    private static final List<TokenDiff> ONE_TOKEN = List.of(new TokenDiff(MINT, 750L, 0L));

    @Test
    void theItemsEqualToTheMeasureAreAccepted() {
        assertThat(CompensationRules.check(
                        List.of(new Compensation(Compensation.SOL, 1_000L), new Compensation(MINT, 750L)),
                        1_000L,
                        ONE_TOKEN))
                .isEmpty();
    }

    /** S14: a wrong value is refused; the divergence stays OPEN. */
    @Test
    void aValueDifferentFromTheMeasureIsRefused() {
        assertThat(CompensationRules.check(List.of(new Compensation(Compensation.SOL, 999L)), 1_000L, List.of()))
                .contains(CompensationRules.MISMATCH);
        assertThat(CompensationRules.check(List.of(new Compensation(Compensation.SOL, -1_000L)), 1_000L, List.of()))
                .contains(CompensationRules.MISMATCH);
    }

    @Test
    void aMissingAssetOrAnAssetThatDidNotDivergeIsRefused() {
        assertThat(CompensationRules.check(List.of(new Compensation(Compensation.SOL, 1_000L)), 1_000L, ONE_TOKEN))
                .contains(CompensationRules.MISMATCH);
        assertThat(CompensationRules.check(
                        List.of(new Compensation(Compensation.SOL, 1_000L), new Compensation(MINT, 5L)),
                        1_000L,
                        List.of()))
                .contains(CompensationRules.MISMATCH);
    }

    @Test
    void anAssetTwiceOrNoItemAtAllIsRefused() {
        assertThat(CompensationRules.check(
                        List.of(new Compensation(Compensation.SOL, 1_000L), new Compensation(Compensation.SOL, 1_000L)),
                        1_000L,
                        List.of()))
                .contains(CompensationRules.MISMATCH);
        assertThat(CompensationRules.check(List.of(), 1_000L, List.of())).contains(CompensationRules.MISMATCH);
    }

    @Test
    void aNegativeDifferenceIsCompensatedWithItsSign() {
        assertThat(CompensationRules.check(
                        List.of(new Compensation(MINT, -10L)), 0L, List.of(new TokenDiff(MINT, 0L, 10L))))
                .isEmpty();
    }

    @Test
    void theIdempotencyKeyOfAnItemComesFromTheDivergenceTheMeasureAndTheAsset() {
        java.util.UUID divergence = java.util.UUID.randomUUID();
        java.util.UUID run = java.util.UUID.randomUUID();

        assertThat(CompensationRules.idempotencyKey(divergence, run, Compensation.SOL))
                .isEqualTo("comp:" + divergence + ":" + run + ":SOL");
        assertThat(CompensationRules.idempotencyKey(divergence, run, MINT)).endsWith(":" + MINT);
    }
}
