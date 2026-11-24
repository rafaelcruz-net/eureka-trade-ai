package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.OwnHoldingsScenario;
import com.memetrade.pnl.api.HoldingsResult;
import com.memetrade.pnl.api.RealBotPnlLine;
import com.memetrade.pnl.application.HoldingsReaders;
import com.memetrade.pnl.real.ReconciliationResult;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U4, Etapa 4, T1 (written before the code; E1, E2, AC6.1.1, AC6.1.2; BR1.1, BR1.2, BR1.4,
 * BR1.7, BR7.2): with the flag on, the cycle of A sells only the 100 XYZ A bought; the order carries 100,000,000 base
 * units; the sale realizes +100,000,000 lamports and +5,000 of fees for A, with no part "sold by another bot"; then A
 * owns nothing and B keeps 300 XYZ at 450,000,000; the bots plus "Sem bot" match the REAL ledger exactly.
 */
@Tag("u4-own-quantity-cycle")
class OwnQuantitySellIT extends AbstractOwnQuantityIT {

    @Test
    void e1TheCycleOfASellsOnlyTheQuantityABoughtAndE2LeavesBIntact() {
        Pair pair = pair();
        OwnQuantityCycle cycle = cycle(true).sellsAll(pair.xyz());

        CycleResult result = cycle.run(pair.refA(), slot());

        assertThat(outcome(result)).isEqualTo("REAL_RECORDED");
        assertThat(OwnQuantityCycle.mints(cycle.lastRequest().positions())).containsExactly(pair.xyz());
        assertThat(cycle.lastRequest().positions().getFirst().quantity()).isEqualByComparingTo("100");
        assertThat(sellDecisions(pair.a())).containsExactly(A_UNITS);

        RealBotPnlLine aBefore = line(pair, pair.a());
        UUID order = settleSale(pendingSellOf(pair.a()), A_UNITS, 200_000_000L, 5_000L, 5_000);

        assertThat(state(order)).isEqualTo("CONFIRMED");
        assertThat(jdbc.sql("select input_base_units from real_order where real_order_id = :id")
                        .param("id", order)
                        .query(Long.class)
                        .single())
                .isEqualTo(A_UNITS);
        RealBotPnlLine aAfter = line(pair, pair.a());
        assertThat(aAfter.realizedLamports() - aBefore.realizedLamports()).isEqualTo(100_000_000L);
        assertThat(aAfter.feesLamports() - aBefore.feesLamports()).isEqualTo(5_000L);
        assertThat(line(pair, pair.b()).shares())
                .as("no part sold by another bot")
                .noneMatch(share -> "SOLD_BY_OTHER_BOT".equals(share.origin()));
        assertThat(reconciliation(pair)).isEqualTo(ReconciliationResult.Status.MATCH);

        // E2: A owns nothing of XYZ; B keeps 300 XYZ at 450,000,000.
        HoldingsResult a =
                HoldingsReaders.of(context).holdings(pair.tenantId(), pair.userId(), pair.a(), Duration.ofMillis(250));
        HoldingsResult b =
                HoldingsReaders.of(context).holdings(pair.tenantId(), pair.userId(), pair.b(), Duration.ofMillis(250));
        assertThat(OwnHoldingsScenario.own(a)).doesNotContainKey(pair.xyz());
        assertThat(OwnHoldingsScenario.own(b).get(pair.xyz())).containsExactly(B_UNITS, B_COST);
    }

    @Test
    void theNextCycleOfAAfterTheSaleSellsNothing() {
        Pair pair = pair();
        OwnQuantityCycle cycle = cycle(true).sellsAll(pair.xyz());
        cycle.run(pair.refA(), slot());
        settleSale(pendingSellOf(pair.a()), A_UNITS, 200_000_000L, 5_000L, 5_000);

        CycleResult next = cycle.run(pair.refA(), nextSlot());

        assertThat(outcome(next)).isEqualTo("NO_ORDER");
        assertThat(cycle.lastRequest().positions()).isEmpty();
        assertThat(sellDecisions(pair.a())).containsExactly(A_UNITS);
    }
}
