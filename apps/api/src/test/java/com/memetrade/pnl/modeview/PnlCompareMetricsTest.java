package com.memetrade.pnl.modeview;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.api.PnlMarket;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U3, Etapa 12 (test after the layer; NFR3.11, NFR3.15): the counters of C6 are registered with
 * 0 at start-up (1 + 10 series); each 422 counts one; a side past its deadline counts only its own series, and the
 * series of the blocks of C4 never move because of C6.
 */
class PnlCompareMetricsTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PnlCompareMetrics metrics = new PnlCompareMetrics(meters);
    private final PnlModeMetrics blocks = new PnlModeMetrics(meters);

    @Test
    void elevenSeriesAreRegisteredWithZero() {
        assertThat(meters.find(PnlCompareMetrics.TEMPLATE_MISMATCHES).counters())
                .hasSize(1);
        assertThat(meters.find(PnlCompareMetrics.SIDE_UNAVAILABLE).counters())
                .hasSize(10)
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
        assertThat(meters.find(PnlCompareMetrics.TEMPLATE_MISMATCHES).counter().count())
                .isZero();
    }

    @Test
    void twoRefusalsAre2() {
        metrics.templateMismatch();
        metrics.templateMismatch();

        assertThat(meters.find(PnlCompareMetrics.TEMPLATE_MISMATCHES).counter().count())
                .isEqualTo(2);
    }

    @Test
    void aTimeoutOfTheRealSideCountsOnlyItsSeriesAndNeverTheBlocksOfC4() {
        metrics.sideUnavailable(PnlMarket.REAL, PnlModeMetrics.Cause.TIMEOUT);

        assertThat(side("REAL", "TIMEOUT")).isEqualTo(1);
        assertThat(meters.find(PnlCompareMetrics.SIDE_UNAVAILABLE).counters().stream()
                        .mapToDouble(Counter::count)
                        .sum())
                .isEqualTo(1);
        assertThat(meters.find(PnlModeMetrics.UNAVAILABLE).counters().stream()
                        .mapToDouble(Counter::count)
                        .sum())
                .isZero();
        blocks.unavailable(PnlMarket.PAPER, PnlModeMetrics.Cause.ERROR);
        assertThat(side("PAPER", "ERROR")).isZero();
    }

    private double side(String market, String cause) {
        return meters.find(PnlCompareMetrics.SIDE_UNAVAILABLE)
                .tag("market", market)
                .tag("cause", cause)
                .counter()
                .count();
    }
}
