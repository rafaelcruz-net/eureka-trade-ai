package com.memetrade.realexecution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 5 of the U4, written BEFORE the code (BR3.2, BR4.2, BR4.3, NFR10.1): the minimum output, the freshness of a
 * quote, the signing deadline and the fee, in exact integers (never floating point).
 */
@Tag("u4-real-execution")
class QuoteMathTest {

    private static final Instant T0 = Instant.parse("2026-11-15T12:00:00Z");

    @Test
    void theMinimumOutputIsTheFloorOfTheQuoteMinusTheSlippage() {
        assertThat(QuoteMath.minOut(123_456_789L, 200)).isEqualTo(120_987_653L);
        assertThat(QuoteMath.minOut(10_000L, 500)).isEqualTo(9_500L);
        assertThat(QuoteMath.minOut(1L, 1)).isZero();
    }

    @Test
    void theMinimumOutputIsExactForTheLargestQuotes() {
        assertThat(QuoteMath.minOut(Long.MAX_VALUE, 200))
                .isEqualTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)
                        .multiply(java.math.BigInteger.valueOf(9_800))
                        .divide(java.math.BigInteger.valueOf(10_000))
                        .longValueExact());
    }

    @Test
    void aSlippageOutsideOneToFiveHundredIsRefused() {
        assertThatThrownBy(() -> QuoteMath.minOut(1_000L, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuoteMath.minOut(1_000L, 501)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuoteMath.minOut(-1L, 200)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aQuoteOfTenSecondsIsStillFreshAndOfMoreIsNot() {
        Duration max = Duration.ofSeconds(10);

        assertThat(QuoteMath.fresh(T0, T0.plusSeconds(10), max)).isTrue();
        assertThat(QuoteMath.fresh(T0, T0.plusMillis(10_001), max)).isFalse();
    }

    @Test
    void theSigningDeadlineIsEightSecondsAfterTheQuote() {
        assertThat(QuoteMath.signingDeadline(T0, Duration.ofSeconds(8))).isEqualTo(T0.plusSeconds(8));
    }

    @Test
    void theFeeIsTheBaseFeePerSignaturePlusTheCeilingOfThePriority() {
        // 5000 x 1 + ceil(12345 x 280000 / 10^6) = 5000 + 3457
        assertThat(QuoteMath.feeLamports(1, 280_000, 12_345L)).isEqualTo(8_457L);
        assertThat(QuoteMath.feeLamports(2, 280_000, 12_345L)).isEqualTo(13_457L);
        assertThat(QuoteMath.feeLamports(1, 200_000, 0L)).isEqualTo(5_000L);
        assertThat(QuoteMath.priorityFeeLamports(1_400_000, 1_000_000L)).isEqualTo(1_400_000L);
    }

    @Test
    void theQuotedInputOfABuyNeverPassesItsReservation() {
        assertThat(QuoteMath.withinReservation(50_000_000L, 50_000_000L)).isTrue();
        assertThat(QuoteMath.withinReservation(50_000_001L, 50_000_000L)).isFalse();
    }

    @Test
    void theSlippageOfThePersonIsUsedAndTheDefaultIsTwoHundred() {
        assertThat(QuoteMath.slippageOf(0)).isEqualTo(200);
        assertThat(QuoteMath.slippageOf(350)).isEqualTo(350);
        assertThat(QuoteMath.slippageOf(900)).isEqualTo(500);
    }
}
