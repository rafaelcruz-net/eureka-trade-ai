package com.memetrade.bots.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.domain.SellCapOutcome;
import com.memetrade.bots.worker.OwnHoldingsStreaks;
import com.memetrade.pnl.api.UnavailableReason;
import com.memetrade.testsupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U4, Etapa 16 (after the layer; NFR2.10, NFR2.11, NFR2.16, NFR2.18): the three counters and the
 * gauge of the own quantity exist with 0 from the construction (7 series and the gauge); an unavailable read counts in
 * the reads and in its reason only; the cut counts its outcome; STALE_SNAPSHOT never counts as unavailable.
 */
class OwnQuantityMetricsTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OwnQuantityMetrics metrics =
            new OwnQuantityMetrics(meters, new OwnHoldingsStreaks(new MutableClock()));

    @Test
    void everySeriesExistsWithZeroFromTheStart() {
        assertThat(meters.get("real_own_holdings_reads").counters()).hasSize(1);
        assertThat(meters.get("real_own_holdings_unavailable").counters()).hasSize(3);
        assertThat(meters.get("real_cycle_sell_capped").counters()).hasSize(3);
        assertThat(meters.get("real_own_holdings_stuck_bots").gauge().value()).isZero();
        meters.getMeters().forEach(meter -> meter.measure()
                .forEach(m -> assertThat(m.getValue()).isZero()));
    }

    @Test
    void aReadPastItsBudgetCountsTheReadAndItsReasonOnly() {
        metrics.read();
        metrics.unavailable(UnavailableReason.BUDGET_EXCEEDED);

        assertThat(count("real_own_holdings_reads", null, null)).isEqualTo(1.0);
        assertThat(count("real_own_holdings_unavailable", "reason", "BUDGET_EXCEEDED"))
                .isEqualTo(1.0);
        assertThat(count("real_own_holdings_unavailable", "reason", "READ_FAILED"))
                .isZero();
        assertThat(count("real_own_holdings_unavailable", "reason", "BOT_NOT_OWNED"))
                .isZero();
    }

    @Test
    void anAvailableReadCountsOnlyTheRead() {
        metrics.read();

        assertThat(count("real_own_holdings_reads", null, null)).isEqualTo(1.0);
        assertThat(meters.get("real_own_holdings_unavailable").counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    void theCutCountsItsOutcomeAndStaleIsNeverUnavailable() {
        metrics.capped(SellCapOutcome.CAPPED);
        metrics.capped(SellCapOutcome.STALE_SNAPSHOT);

        assertThat(count("real_cycle_sell_capped", "outcome", "CAPPED")).isEqualTo(1.0);
        assertThat(count("real_cycle_sell_capped", "outcome", "STALE_SNAPSHOT")).isEqualTo(1.0);
        assertThat(count("real_cycle_sell_capped", "outcome", "NO_OWN_QUANTITY"))
                .isZero();
        assertThat(meters.get("real_own_holdings_unavailable").counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    void noLabelCarriesABotATenantOrAMint() {
        meters.getMeters().forEach(meter -> assertThat(meter.getId().getTags())
                .allSatisfy(tag -> assertThat(tag.getKey()).isIn("reason", "outcome")));
    }

    private double count(String name, String tag, String value) {
        var search = meters.get(name);
        if (tag != null) {
            search = search.tag(tag, value);
        }
        return search.counter().count();
    }
}
