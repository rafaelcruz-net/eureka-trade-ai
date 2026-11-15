package com.memetrade.bots.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 7 of the U4, written BEFORE the code (BR1.2, NFR10.1, project.md "NEVER usar ponto flutuante", "unknown nunca
 * zero"): the decision engine works in dollars and whole tokens; the REAL order travels in lamports and base units.
 * The conversion is exact, rounds against spending more, and an unknown price never becomes zero: there is no order.
 */
@Tag("u4-real-execution")
class RealBotSizingTest {

    @Test
    void aBuyOfTokensBecomesTheLamportsOfItsValueRoundedDown() {
        // 1000 tokens at 0.15 dollar with SOL at 150 dollars: 1 SOL.
        assertThat(RealSizing.buyLamports(
                        new BigDecimal("1000"),
                        Optional.of(new BigDecimal("0.15")),
                        Optional.of(new BigDecimal("150"))))
                .contains(1_000_000_000L);
        // 1 token at 0.0001 dollar with SOL at 150: 666.666... lamports, down to 666.
        assertThat(RealSizing.buyLamports(
                        BigDecimal.ONE, Optional.of(new BigDecimal("0.0001")), Optional.of(new BigDecimal("150"))))
                .contains(666L);
    }

    @Test
    void anUnknownPriceOfTheTokenOrOfSolGivesNoOrderNeverZero() {
        assertThat(RealSizing.buyLamports(BigDecimal.TEN, Optional.empty(), Optional.of(new BigDecimal("150"))))
                .isEmpty();
        assertThat(RealSizing.buyLamports(BigDecimal.TEN, Optional.of(BigDecimal.ONE), Optional.empty()))
                .isEmpty();
        assertThat(RealSizing.buyLamports(
                        new BigDecimal("0.000001"),
                        Optional.of(new BigDecimal("0.0001")),
                        Optional.of(new BigDecimal("150"))))
                .as("less than one lamport is no order")
                .isEmpty();
    }

    @Test
    void aSellOfAWholePositionSellsItsExactBaseUnits() {
        assertThat(RealSizing.baseUnits(new BigDecimal("1.234567"), 6)).contains(1_234_567L);
        assertThat(RealSizing.baseUnits(new BigDecimal("0.0000009"), 6)).isEmpty();
    }

    @Test
    void lamportsAndBaseUnitsBecomeDollarsAndTokensForTheDecision() {
        assertThat(RealSizing.tokens(1_234_567L, 6)).isEqualByComparingTo("1.234567");
        assertThat(RealSizing.dollarsOfLamports(2_000_000_000L, new BigDecimal("150")))
                .isEqualByComparingTo("300");
    }
}
