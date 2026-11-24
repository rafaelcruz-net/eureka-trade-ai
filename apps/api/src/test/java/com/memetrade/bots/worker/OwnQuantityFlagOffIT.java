package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.real.ReconciliationResult;
import io.micrometer.core.instrument.Meter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U4, Etapa 5, T7 (written before the code; E9; BR5.1, BR5.3, AC6.2.2): with the flag off the
 * cycle is today's: no read of the own holdings, the inputs and the recorded JSON as today (no key {@code ownQuantity}),
 * the sale of the whole position of the wallet (400 XYZ, the excess of 300 XYZ prorated to B as "sold by another bot"),
 * every metric of the U4 at zero and no new outcome of the cycle.
 */
@Tag("u4-own-quantity-cycle")
class OwnQuantityFlagOffIT extends AbstractOwnQuantityIT {

    @Test
    void e9WithTheFlagOffACycleSellsTheWholeWalletPositionAsToday() {
        Pair pair = pair();
        OwnQuantityCycle cycle = cycle(false).sellsAll(pair.xyz());

        CycleResult result = cycle.run(pair.refA(), slot());

        assertThat(cycle.holdings.calls.get()).isZero();
        assertThat(cycle.lastRequest().positions().getFirst().quantity()).isEqualByComparingTo("400");
        assertThat(context(result)).doesNotContain("ownQuantity");
        assertThat(outcome(result)).isEqualTo("REAL_RECORDED");
        assertThat(sellDecisions(pair.a())).containsExactly(A_UNITS + B_UNITS);
        for (Meter meter : cycle.meters.getMeters()) {
            meter.measure().forEach(measure -> assertThat(measure.getValue())
                    .as(meter.getId().getName())
                    .isZero());
        }

        settleSale(pendingSellOf(pair.a()), A_UNITS + B_UNITS, 800_000_000L, 5_000L, 5_000);

        assertThat(line(pair, pair.b()).shares())
                .filteredOn(share -> "SOLD_BY_OTHER_BOT".equals(share.origin()))
                .singleElement()
                .satisfies(share -> assertThat(share.tokenBaseUnits()).isEqualTo(B_UNITS));
        assertThat(reconciliation(pair)).isEqualTo(ReconciliationResult.Status.MATCH);
    }
}
