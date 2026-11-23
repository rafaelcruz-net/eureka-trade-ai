package com.memetrade.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.ledger.api.RealCost;
import com.memetrade.ledger.api.RealCostUnknownReason;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealPosition;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U2, Etapa 8 (written before the code; BR2.5, BR5.3, F-3): the quantity-only path of the
 * wallet, pure, in the ledger, fed with the swap fills in network order and no attribution. Each IN adds its quantity;
 * each OUT removes min(sold, current), so a sale beyond what is held never makes it negative (the excess is the share of
 * UNOWNED without cost in the P&L); a position at zero disappears; the cost is never read here
 * ({@code Unknown(NOT_READ)}), never 0.
 */
class RealCostMethodQuantityTest {

    private static final RealCost NOT_READ = new RealCost.Unknown(RealCostUnknownReason.NOT_READ);

    private static RealPositions.Fill in(String mint, long units) {
        return new RealPositions.Fill(RealEntryKind.SWAP_FILL_IN, -units, mint, units);
    }

    private static RealPositions.Fill out(String mint, long units) {
        return new RealPositions.Fill(RealEntryKind.SWAP_FILL_OUT, units, mint, units);
    }

    @Test
    void eachBuyAddsItsQuantity() {
        assertThat(RealPositions.of(List.of(in("A", 100), in("A", 100))))
                .containsExactly(new RealPosition("A", 200, NOT_READ));
    }

    @Test
    void eachSaleRemovesWhatItSoldUpToWhatIsHeld() {
        assertThat(RealPositions.of(List.of(in("A", 200), out("A", 50))))
                .containsExactly(new RealPosition("A", 150, NOT_READ));
    }

    @Test
    void aSaleBeyondWhatIsHeldNeverMakesTheQuantityNegative() {
        RealPositions.Quantities quantities = new RealPositions.Quantities();
        quantities.apply(in("A", 10));
        quantities.apply(out("A", 12));
        quantities.apply(in("A", 5));

        assertThat(quantities.positions()).containsExactly(new RealPosition("A", 5, NOT_READ));
    }

    @Test
    void aPositionAtZeroDisappearsAndTheOtherMintsStay() {
        assertThat(RealPositions.of(List.of(in("A", 10), in("B", 7), out("A", 10))))
                .containsExactly(new RealPosition("B", 7, NOT_READ));
    }

    @Test
    void aSaleOfAMintNeverBoughtLeavesNothing() {
        assertThat(RealPositions.of(List.of(out("Z", 3)))).isEmpty();
    }

    @Test
    void onlySwapFillsMoveTheQuantity() {
        RealPositions.Quantities quantities = new RealPositions.Quantities();

        assertThatThrownBy(() -> quantities.apply(new RealPositions.Fill(RealEntryKind.NETWORK_FEE, -5, "A", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> quantities.apply(new RealPositions.Fill(RealEntryKind.SWAP_FILL_IN, -5, "A", 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
