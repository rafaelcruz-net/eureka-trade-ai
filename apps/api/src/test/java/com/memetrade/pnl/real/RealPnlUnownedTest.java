package com.memetrade.pnl.real;

import static com.memetrade.pnl.real.PnlScenario.XYZ;
import static com.memetrade.pnl.real.PnlScenario.line;
import static com.memetrade.pnl.real.PnlScenario.price;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.pnl.api.LamportsValue;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.ProratedShare;
import com.memetrade.pnl.api.UnownedPnlLine;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U2, Etapa 18 (WRITTEN BEFORE THE CODE; BR3.1, BR3.2, BR3.4, BR5.1 to BR5.5, BR8.5): the line
 * "Sem bot" holds everything of the holder UNOWNED, always with the reasons that produced it, and an order that names a
 * bot outside the person's directory is a defect that never goes to that line.
 */
class RealPnlUnownedTest {

    private static final Holder A = RealCostBasisTest.A;
    private static final Holder B = RealCostBasisTest.B;

    private static PnlScenario ab() {
        return new PnlScenario().bot(A).bot(B);
    }

    private static void assertMatch(RealPnlResult result) {
        assertThat(result.reconciliation().status())
                .as("%s", result.reconciliation())
                .isEqualTo(ReconciliationResult.Status.MATCH);
    }

    /** BR5.5: with nothing of UNOWNED since the beginning, the line is absent. */
    @Test
    void withoutAnythingOfUnownedTheLineIsAbsent() {
        PnlScenario ledger = ab();
        ledger.buy(A, 10, 10L, 1L);
        ledger.ownSale(A, 5, 20L, 1L);

        assertThat(ledger.all().pnl().unowned()).isEmpty();
    }

    /** BR3.1, BR5.4: a buy whose order is gone belongs to UNOWNED with ORDER_RECORD_MISSING, open at its cost. */
    @Test
    void aBuyWhoseOrderIsGoneIsUnownedWithOrderRecordMissing() {
        PnlScenario ledger = ab();
        ledger.purgedBuy(50, 75_000_000L);

        RealPnlResult result = ledger.run(PnlWindow.all(), price(XYZ, 2_000_000L));

        UnownedPnlLine unowned = result.pnl().unowned().orElseThrow();
        assertThat(unowned.reasons()).containsExactly("ORDER_RECORD_MISSING");
        assertThat(unowned.unrealized()).isEqualTo(new LamportsValue.Known(100_000_000L - 75_000_000L));
        assertThat(unowned.realizedLamports()).isZero();
        assertMatch(result);
    }

    /** BR3.2, BR5.4: a sale whose order is gone has UNOWNED as seller; its excess goes to the bots. */
    @Test
    void aSaleWhoseOrderIsGoneSellsTheUnownedQuantityFirstAndTheExcessGoesToTheBots() {
        PnlScenario ledger = ab();
        ledger.purgedBuy(10, 50L);
        ledger.buy(A, 10, 100L, 0);
        ledger.purgedSale(25, 250L, 5L);

        RealPnlResult result = ledger.all();

        assertThat(line(result, A).realizedLamports()).isZero();
        assertThat(line(result, A).shares()).extracting(ProratedShare::origin).containsExactly("SOLD_BY_OTHER_BOT");
        UnownedPnlLine unowned = result.pnl().unowned().orElseThrow();
        assertThat(unowned.realizedLamports()).isEqualTo(50L + 50L);
        assertThat(unowned.feesLamports()).isEqualTo(3L);
        assertThat(unowned.reasons()).containsExactlyInAnyOrder("ORDER_RECORD_MISSING", "UNOWNED_NO_COST");
        assertMatch(result);
    }

    /** BR5.4: the fee of a failed order whose record is gone goes to "Sem bot" with ORDER_RECORD_MISSING. */
    @Test
    void theFeeOfAnOrderWhoseRecordIsGoneGoesToTheLineWithoutBot() {
        PnlScenario ledger = ab();
        ledger.purgedFailed(5_000L);

        RealPnlResult result = ledger.all();

        UnownedPnlLine unowned = result.pnl().unowned().orElseThrow();
        assertThat(unowned.feesLamports()).isEqualTo(5_000L);
        assertThat(unowned.reasons()).containsExactly("ORDER_RECORD_MISSING");
        assertMatch(result);
    }

    /** BR5.1, BR5.2: the registered part of UNOWNED is sold with its own cost and UNOWNED_EXCESS. */
    @Test
    void theRegisteredPartOfUnownedIsSoldAtItsCostWithUnownedExcess() {
        PnlScenario ledger = ab();
        ledger.purgedBuy(50, 75_000_000L);
        ledger.buy(A, 100, 100_000_000L, 0);
        ledger.ownSale(A, 125, 250_000_000L, 0);

        RealPnlResult result = ledger.run(PnlWindow.all(), price(XYZ, 2_000_000L));

        UnownedPnlLine unowned = result.pnl().unowned().orElseThrow();
        // UNOWNED gives 25 of its 50: received 50.000.000, cost ceil(75M x 25 / 50) = 37.500.000.
        assertThat(unowned.realizedLamports()).isEqualTo(12_500_000L);
        assertThat(unowned.unrealized()).isEqualTo(new LamportsValue.Known(50_000_000L - 37_500_000L));
        assertThat(unowned.reasons()).containsExactlyInAnyOrder("ORDER_RECORD_MISSING", "UNOWNED_EXCESS");
        assertMatch(result);
    }

    /** BR5.3: what a sale takes beyond every position: UNOWNED, no cost, no open position; realized = received. */
    @Test
    void theQuantityBeyondEveryPositionHasNoCostAndOpensNothing() {
        PnlScenario ledger = ab();
        ledger.liquidation(7, 700L, 0);

        RealPnlResult result = ledger.all();

        UnownedPnlLine unowned = result.pnl().unowned().orElseThrow();
        assertThat(unowned.realizedLamports()).isEqualTo(700L);
        assertThat(unowned.reasons()).containsExactly("UNOWNED_NO_COST");
        assertThat(result.positions()).isEmpty();
        assertMatch(result);
    }

    /** BR3.4 (decision D3): an order whose bot is not a REAL bot of the person stops the calculation; never "Sem bot". */
    @Test
    void anOrderOfABotOutsideTheDirectoryIsUnknownBotAndNeverGoesToTheLineWithoutBot() {
        Holder stranger = Holder.bot(UUID.randomUUID(), Instant.parse("2026-11-03T10:00:00Z"));
        PnlScenario ledger = ab();
        ledger.buy(stranger, 10, 10L, 0);

        assertThatThrownBy(ledger::all)
                .isInstanceOf(RealPnlEngine.UnknownBot.class)
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(stranger.toString()));
    }
}
