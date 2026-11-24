package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.realexecution.OpenSellsFixture;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U4, Etapa 4, T8 (written before the code; C2, BR3.4, NFR2.5): a SELL order of A of 100 XYZ is
 * SENT when the snapshot starts and settles during the cycle (the ledger and the state of the order change in one
 * transaction). The snapshot saw it open, so the cycle sells nothing; the next cycle sees the own quantity at zero in
 * the ledger. A sale is never counted twice nor forgotten. With the read of the effect on the network failing, the
 * order stays SENT, counts as open and the available quantity stays zero.
 */
@Tag("u4-own-quantity-cycle")
class OwnQuantitySettlementRaceIT extends AbstractOwnQuantityIT {

    @Test
    void aSaleSettledDuringTheCycleIsNeverSoldAgain() {
        Pair pair = pair();
        OwnQuantityCycle cycle = cycle(true).sellsAll(pair.xyz());
        // The own sale of A of its 100 XYZ, taken by the real queue and driver to SENT.
        cycle.run(pair.refA(), slot());
        UUID request = pendingSellOf(pair.a());
        consumer.runOnce();
        UUID order = orderOf(request);
        driver.runOnce();
        assertThat(state(order)).isEqualTo("SENT");
        // The real confirmation settles it right after the snapshot of the next cycle and before its transaction.
        cycle.holdings.after = () -> confirm(order);

        CycleResult during = cycle.run(pair.refA(), nextSlot());

        assertThat(state(order)).isEqualTo("CONFIRMED");
        assertThat(outcome(during)).isEqualTo("NO_ORDER");
        assertThat(cycle.lastRequest().positions()).isEmpty();
        assertThat(sellDecisions(pair.a())).containsExactly(A_UNITS);

        cycle.holdings.after = () -> {};
        CycleResult next = cycle.run(pair.refA(), nextSlot());

        assertThat(outcome(next)).isEqualTo("NO_ORDER");
        assertThat(cycle.lastRequest().positions())
                .as("the ledger shows A at zero")
                .isEmpty();
        assertThat(sellDecisions(pair.a()))
                .as("the sale was never counted twice")
                .containsExactly(A_UNITS);
    }

    /** The confirmation of the network, as the job records it: the fill in the ledger and the order CONFIRMED. */
    private void confirm(UUID order) {
        String signature = txSignature(order);
        chain.effect(
                signature,
                new com.memetrade.botwallet.api.RealChain.SwapEffect(
                        199_995_000L, -A_UNITS, 5_000L, true, java.util.OptionalLong.of(5_000)));
        chain.finalize(signature);
        confirmation.runOnce();
    }

    @Test
    void withTheEffectOnTheNetworkUnreadTheOrderStaysSentAndCountsAsOpen() {
        Pair pair = pair();
        UUID order = new OpenSellsFixture(jdbc)
                .order(pair.tenantId(), pair.userId(), pair.a(), "SELL", pair.xyz(), A_UNITS, "SENT");
        chain.down = true;
        confirmation.runOnce();
        chain.down = false;
        assertThat(state(order)).isEqualTo("SENT");
        OwnQuantityCycle cycle = cycle(true).sellsAll(pair.xyz());

        cycle.run(pair.refA(), slot());

        assertThat(cycle.lastRequest().positions()).isEmpty();
        assertThat(sellDecisions(pair.a())).isEmpty();
    }
}
