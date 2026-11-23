package com.memetrade.pnl.real;

import static com.memetrade.pnl.real.PnlScenario.XYZ;
import static com.memetrade.pnl.real.PnlScenario.line;
import static com.memetrade.pnl.real.PnlScenario.price;
import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.api.BotPnlStatus;
import com.memetrade.pnl.api.LamportsValue;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.ProratedShare;
import com.memetrade.pnl.api.RealBotPnlLine;
import com.memetrade.pnl.api.UnownedPnlLine;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U2, Etapa 18 (WRITTEN BEFORE THE CODE; BR9.4, FR5.4, team.md "P&L com cenários
 * determinísticos"): every worked example of {@code rules.md} as it is, in lamports and base units, read from the
 * ledger rows the confirmations write, in network order. Each one also reconciles exactly with the ledger (BR8.1).
 *
 * <p>Bots of the examples (stories.md): A created 2026-11-01T10:00Z; B and C 2026-11-02T10:00Z, the id of B smaller.
 */
class RealBotPnlExamplesTest {

    private static final Holder A = RealCostBasisTest.A;
    private static final Holder B = RealCostBasisTest.B;
    private static final Holder C = RealCostBasisTest.C;

    private static LamportsValue known(long lamports) {
        return new LamportsValue.Known(lamports);
    }

    private static void assertMatch(RealPnlResult result) {
        assertThat(result.reconciliation().status())
                .as("%s", result.reconciliation())
                .isEqualTo(ReconciliationResult.Status.MATCH);
    }

    private static PnlScenario abc() {
        return new PnlScenario().bot(A).bot(B).bot(C);
    }

    /** AC3.2.1 to AC3.2.4: the own average cost of A; B is another holder; the unrealized of A at 2.500.000. */
    @Test
    void ac321To324TheAverageCostOfTheBotItself() {
        PnlScenario ledger = abc();
        ledger.buy(A, 100, 100_000_000L, 0);
        ledger.buy(A, 100, 300_000_000L, 0);
        ledger.ownSale(A, 50, 150_000_000L, 0);
        ledger.buy(B, 100, 500_000_000L, 0);

        RealPnlResult result = ledger.run(PnlWindow.all(), price(XYZ, 2_500_000L));

        RealBotPnlLine a = line(result, A);
        assertThat(a.realizedLamports()).isEqualTo(50_000_000L);
        assertThat(a.unrealized()).isEqualTo(known(75_000_000L));
        assertThat(a.total()).isEqualTo(known(125_000_000L));
        RealBotPnlLine b = line(result, B);
        assertThat(b.realizedLamports()).isZero();
        assertThat(b.unrealized()).isEqualTo(known(250_000_000L - 500_000_000L));
        assertThat(result.positions())
                .containsExactly(new PositionValue(
                        XYZ,
                        250,
                        800_000_000L,
                        java.util.Optional.of(625_000_000L),
                        java.util.Optional.of(-175_000_000L)));
        assertMatch(result);
    }

    /** AC3.3.1: a liquidation with two bots: A +100.000.000, B 0; fees 2.500 and 7.500; parts LIQUIDATION. */
    @Test
    void ac331ALiquidationWithTwoBots() {
        PnlScenario ledger = abc();
        ledger.buy(A, 100, 200_000_000L, 0);
        ledger.buy(B, 300, 900_000_000L, 0);
        ledger.liquidation(400, 1_200_000_000L, 10_000L);

        RealPnlResult result = ledger.all();

        assertThat(line(result, A).realizedLamports()).isEqualTo(100_000_000L);
        assertThat(line(result, A).feesLamports()).isEqualTo(2_500L);
        assertThat(line(result, B).realizedLamports()).isZero();
        assertThat(line(result, B).feesLamports()).isEqualTo(7_500L);
        assertThat(line(result, A).shares())
                .extracting(ProratedShare::origin, ProratedShare::tokenBaseUnits, ProratedShare::receivedLamports)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("LIQUIDATION", 100L, 300_000_000L));
        assertThat(result.pnl().total().realizedLamports()).isEqualTo(100_000_000L);
        assertMatch(result);
    }

    /** AC3.3.2: three bots with a remainder: A -11.111.111, B +23.333.333, C -4.444.445. */
    @Test
    void ac332ThreeBotsWithARemainder() {
        PnlScenario ledger = abc();
        ledger.buy(A, 50, 400_000_000L, 0);
        ledger.buy(B, 30, 210_000_000L, 0);
        ledger.buy(C, 20, 160_000_000L, 0);
        ledger.liquidation(100, 777_777_777L, 7_000L);

        RealPnlResult result = ledger.all();

        assertThat(line(result, A).realizedLamports()).isEqualTo(-11_111_111L);
        assertThat(line(result, B).realizedLamports()).isEqualTo(23_333_333L);
        assertThat(line(result, C).realizedLamports()).isEqualTo(-4_444_445L);
        assertThat(line(result, A).shares().getFirst().receivedLamports()).isEqualTo(388_888_889L);
        assertThat(line(result, A).feesLamports()).isEqualTo(3_500L);
        assertThat(line(result, B).feesLamports()).isEqualTo(2_100L);
        assertThat(line(result, C).feesLamports()).isEqualTo(1_400L);
        assertThat(result.pnl().total().realizedLamports()).isEqualTo(7_777_777L);
        assertMatch(result);
    }

    /** AC3.4.1: a whole conditional with a tie: A +33.333.334, B -116.666.667, C -266.666.667; parts CONDITIONAL. */
    @Test
    void ac341AWholeConditionalWithATie() {
        PnlScenario ledger = abc();
        ledger.buy(A, 100, 300_000_000L, 0);
        ledger.buy(B, 100, 450_000_000L, 0);
        ledger.buy(C, 100, 600_000_000L, 0);
        ledger.conditional(300, 1_000_000_000L, 5_000L);

        RealPnlResult result = ledger.all();

        assertThat(line(result, A).realizedLamports()).isEqualTo(33_333_334L);
        assertThat(line(result, B).realizedLamports()).isEqualTo(-116_666_667L);
        assertThat(line(result, C).realizedLamports()).isEqualTo(-266_666_667L);
        assertThat(line(result, A).feesLamports()).isEqualTo(1_667L);
        assertThat(line(result, B).feesLamports()).isEqualTo(1_667L);
        assertThat(line(result, C).feesLamports()).isEqualTo(1_666L);
        assertThat(line(result, C).shares()).extracting(ProratedShare::origin).containsExactly("CONDITIONAL");
        assertMatch(result);
    }

    /** AC3.4.2: a partial conditional: A +300.000.000 and B +100.000.000; A 300 (600M) and B 200 (500M) stay open. */
    @Test
    void ac342APartialConditional() {
        PnlScenario ledger = abc();
        ledger.buy(A, 600, 1_200_000_000L, 0);
        ledger.buy(B, 400, 1_000_000_000L, 0);
        ledger.conditional(500, 1_500_000_000L, 5_000L);

        RealPnlResult result = ledger.run(PnlWindow.all(), price(XYZ, 2_000_000L));

        assertThat(line(result, A).realizedLamports()).isEqualTo(300_000_000L);
        assertThat(line(result, B).realizedLamports()).isEqualTo(100_000_000L);
        assertThat(line(result, A).unrealized()).isEqualTo(known(300 * 2_000_000L - 600_000_000L));
        assertThat(line(result, B).unrealized()).isEqualTo(known(200 * 2_000_000L - 500_000_000L));
        assertThat(result.positions().getFirst().tokenBaseUnits()).isEqualTo(500L);
        assertThat(result.positions().getFirst().costLamports()).isEqualTo(1_100_000_000L);
        assertMatch(result);
    }

    /** AC3.5.1: the excess of the sale of A goes to B and C; each realizes +100.000.000. */
    @Test
    void ac351AnExcessWithOtherOwners() {
        PnlScenario ledger = abc();
        ledger.buy(A, 100, 100_000_000L, 0);
        ledger.buy(B, 200, 300_000_000L, 0);
        ledger.buy(C, 100, 100_000_000L, 0);
        ledger.ownSale(A, 400, 800_000_000L, 4_000L);

        RealPnlResult result = ledger.all();

        assertThat(line(result, A).realizedLamports()).isEqualTo(100_000_000L);
        assertThat(line(result, B).realizedLamports()).isEqualTo(100_000_000L);
        assertThat(line(result, C).realizedLamports()).isEqualTo(100_000_000L);
        assertThat(line(result, A).feesLamports()).isEqualTo(1_000L);
        assertThat(line(result, B).feesLamports()).isEqualTo(2_000L);
        assertThat(line(result, A).shares()).isEmpty();
        assertThat(line(result, B).shares()).extracting(ProratedShare::origin).containsExactly("SOLD_BY_OTHER_BOT");
        assertMatch(result);
    }

    /** AC3.5.2: the excess goes to the part without owner: A +100.000.000, "Sem bot" +25.000.000 (UNOWNED_EXCESS). */
    @Test
    void ac352AnExcessWithoutAnotherOwner() {
        PnlScenario ledger = abc();
        ledger.purgedBuy(50, 75_000_000L);
        ledger.buy(A, 100, 100_000_000L, 0);
        ledger.ownSale(A, 150, 300_000_000L, 3_000L);

        RealPnlResult result = ledger.all();

        assertThat(line(result, A).realizedLamports()).isEqualTo(100_000_000L);
        assertThat(line(result, A).feesLamports()).isEqualTo(2_000L);
        UnownedPnlLine unowned = result.pnl().unowned().orElseThrow();
        assertThat(unowned.realizedLamports()).isEqualTo(25_000_000L);
        assertThat(unowned.feesLamports()).isEqualTo(1_000L);
        assertThat(unowned.reasons()).contains("UNOWNED_EXCESS");
        assertThat(result.pnl().total().realizedLamports()).isEqualTo(125_000_000L);
        assertMatch(result);
    }

    /** AC3.5.3: a bot that sells only what it bought: no share and no line "Sem bot". */
    @Test
    void ac353ASaleOfOnlyTheOwnQuantity() {
        PnlScenario ledger = abc();
        ledger.buy(A, 100, 100_000_000L, 0);
        ledger.buy(B, 100, 100_000_000L, 0);
        ledger.ownSale(A, 100, 150_000_000L, 0);

        RealPnlResult result = ledger.all();

        assertThat(result.pnl().lines()).allSatisfy(l -> assertThat(l.shares()).isEmpty());
        assertThat(result.pnl().unowned()).isEmpty();
        assertMatch(result);
    }

    /** AC3.6.1 to AC3.6.3: in the same slot the buy of B (smaller signature) comes first, even recorded after. */
    @Test
    void ac361To363TwoOperationsInTheSameSlot() {
        PnlScenario ledger = abc();
        ledger.buy(A, 100, 100_000_000L, 0);
        ledger.signature("Sig9Sale").ownSale(A, 200, 400_000_000L, 5_000L);
        ledger.sameSlot().signature("Sig1Buy").buy(B, 100, 300_000_000L, 5_000L);

        RealPnlResult result = ledger.all();
        RealPnlResult again = ledger.all();

        assertThat(line(result, A).realizedLamports()).isEqualTo(100_000_000L);
        assertThat(line(result, B).realizedLamports()).isEqualTo(-100_000_000L);
        assertThat(line(result, A).feesLamports()).isEqualTo(2_500L);
        assertThat(line(result, B).feesLamports()).isEqualTo(7_500L);
        assertThat(result.pnl().total().feesLamports()).isEqualTo(10_000L);
        assertThat(again.pnl()).isEqualTo(result.pnl());
        assertMatch(result);
    }

    /** AC3.7.1 to AC3.7.4: a failed order, a fee of the mode Assinada and the fee of a sale without bot. */
    @Test
    void ac371To374TheFeesGoToTheRightBot() {
        PnlScenario ledger = abc();
        ledger.failed(A, 5_000L);
        ledger.signedBuy(B, 100, 200_000_000L, 5_000L);
        ledger.buy(C, 100, 100_000_000L, 0);
        ledger.liquidation(200, 400_000_000L, 2_000L);

        RealPnlResult result = ledger.all();

        assertThat(line(result, A).feesLamports()).isEqualTo(5_000L);
        assertThat(line(result, A).realizedLamports()).isZero();
        assertThat(line(result, B).feesLamports()).isEqualTo(5_000L + 1_000L);
        assertThat(line(result, C).feesLamports()).isEqualTo(1_000L);
        assertThat(line(result, B).realizedLamports()).isZero();
        assertThat(result.pnl().unowned()).isEmpty();
        assertThat(result.pnl().total().feesLamports()).isEqualTo(12_000L);
        assertMatch(result);
    }

    /** AC3.7.2, AC3.7.3: in the mode Assinada B has 100 with cost 200.000.000 and only B gets the 5.000. */
    @Test
    void ac372And373TheFeeOfTheModeAssinadaIsOnlyOnTheBotOfTheOrder() {
        PnlScenario ledger = abc();
        ledger.signedBuy(B, 100, 200_000_000L, 5_000L);

        RealPnlResult result = ledger.run(PnlWindow.all(), price(XYZ, 2_000_000L));

        assertThat(line(result, B).unrealized()).isEqualTo(known(0L));
        assertThat(line(result, B).feesLamports()).isEqualTo(5_000L);
        assertThat(line(result, A).feesLamports()).isZero();
        assertThat(line(result, C).feesLamports()).isZero();
        assertThat(result.pnl().unowned()).isEmpty();
        assertMatch(result);
    }

    /** AC3.8.1 to AC3.8.4: an unknown price leaves only the unrealized and the total unknown, never zero. */
    @Test
    void ac381To384AnUnknownPriceNeverBecomesZero() {
        PnlScenario ledger = abc();
        ledger.buy(A, 150, 300_000_000L, 5_000L);
        ledger.ownSale(A, 50, 150_000_000L, 5_000L);

        RealPnlResult unknown = ledger.all();
        RealPnlResult known = ledger.run(PnlWindow.all(), price(XYZ, 2_500_000L));

        RealBotPnlLine a = line(unknown, A);
        assertThat(a.realizedLamports()).isEqualTo(50_000_000L);
        assertThat(a.feesLamports()).isEqualTo(10_000L);
        assertThat(a.unrealized()).isInstanceOf(LamportsValue.Unknown.class);
        assertThat(a.total()).isInstanceOf(LamportsValue.Unknown.class);
        assertThat(unknown.pnl().total().total()).isInstanceOf(LamportsValue.Unknown.class);
        assertThat(unknown.pnl().total().realizedLamports()).isEqualTo(50_000_000L);
        assertThat(unknown.positions().getFirst().costLamports()).isEqualTo(200_000_000L);
        assertThat(line(known, A).unrealized()).isEqualTo(known(50_000_000L));
        assertMatch(unknown);
        assertMatch(known);
    }

    /** AC3.9.1 to AC3.9.3: a removed bot and a paused bot keep their lots, their P&L and their line. */
    @Test
    void ac391To393ARemovedOrPausedBotKeepsItsLotsAndItsLine() {
        PnlScenario ledger =
                new PnlScenario().bot(A).bot(B, BotPnlStatus.PAUSED).bot(C, BotPnlStatus.REMOVED);
        ledger.buy(A, 100, 100_000_000L, 0);
        ledger.buy(C, 100, 120_000_000L, 0);
        ledger.buy(B, 10, 1_000L, 0);
        ledger.liquidation(200, 300_000_000L, 2_000L);

        RealPnlResult result = ledger.all();

        // 200 over A 100, B 10 and C 100: A 95, B 10 (largest remainder), C 95; the paused B gives its whole lot.
        assertThat(line(result, B).status()).isEqualTo(BotPnlStatus.PAUSED);
        assertThat(line(result, B).shares())
                .extracting(ProratedShare::tokenBaseUnits)
                .containsExactly(10L);
        assertThat(line(result, C).status()).isEqualTo(BotPnlStatus.REMOVED);
        assertThat(line(result, C).shares())
                .extracting(ProratedShare::tokenBaseUnits)
                .containsExactly(95L);
        assertThat(result.pnl().lines()).extracting(RealBotPnlLine::botId).hasSize(3);
        assertMatch(result);
    }

    /** AC3.9.1 exactly: A 100 (100M) and C 100 (120M) removed; the liquidation gives A +50M and C +30M. */
    @Test
    void ac391TheRemovedBotTakesItsPartOfTheLiquidation() {
        PnlScenario ledger = new PnlScenario().bot(A).bot(C, BotPnlStatus.REMOVED);
        ledger.buy(A, 100, 100_000_000L, 0);
        ledger.buy(C, 100, 120_000_000L, 0);
        ledger.liquidation(200, 300_000_000L, 2_000L);

        RealPnlResult result = ledger.all();

        assertThat(line(result, A).realizedLamports()).isEqualTo(50_000_000L);
        assertThat(line(result, A).feesLamports()).isEqualTo(1_000L);
        assertThat(line(result, C).realizedLamports()).isEqualTo(30_000_000L);
        assertThat(line(result, C).feesLamports()).isEqualTo(1_000L);
        assertThat(line(result, C).status()).isEqualTo(BotPnlStatus.REMOVED);
        assertThat(result.pnl().total().realizedLamports()).isEqualTo(80_000_000L);
        assertMatch(result);
    }

    /** AC5.1.1 to AC5.1.3: the conditional enters the REAL P&L, read from the ledger by {@code conditional:<id>}. */
    @Test
    void ac511TheConditionalEntersTheRealPnl() {
        PnlScenario ledger = abc();
        ledger.buy(A, 100, 100_000_000L, 0);
        ledger.conditional(100, 180_000_000L, 5_000L);

        RealPnlResult result = ledger.all();

        assertThat(result.pnl().total().realizedLamports()).isEqualTo(80_000_000L);
        assertThat(result.pnl().total().feesLamports()).isEqualTo(5_000L);
        assertMatch(result);
    }

    /** AC5.3.1: A (+100M, +20M, 5.000), B (-30M, -4M, 2.000), "Sem bot" (+25M, 0, 1.000) add up to the mode. */
    @Test
    void ac531SinceTheBeginningTheLinesAddUpToTheModeByTheLedger() {
        PnlScenario ledger = abc();
        ledger.purgedBuy(50, 125_000_000L);
        ledger.buy(A, 50, 50_000_000L, 1_000L);
        ledger.ownSale(A, 100, 300_000_000L, 2_000L);
        ledger.buy(A, 100, 80_000_000L, 3_000L);
        ledger.buy(B, 200, 208_000_000L, 1_000L);
        ledger.ownSale(B, 100, 74_000_000L, 1_000L);

        RealPnlResult result = ledger.run(PnlWindow.all(), price(XYZ, 1_000_000L));

        assertThat(line(result, A).total()).isEqualTo(known(119_995_000L));
        assertThat(line(result, B).total()).isEqualTo(known(-34_002_000L));
        UnownedPnlLine unowned = result.pnl().unowned().orElseThrow();
        assertThat(unowned.realizedLamports()).isEqualTo(25_000_000L);
        assertThat(unowned.feesLamports()).isEqualTo(1_000L);
        assertThat(unowned.reasons()).contains("UNOWNED_EXCESS");
        assertThat(result.pnl().total().realizedLamports()).isEqualTo(95_000_000L);
        assertThat(result.pnl().total().unrealized()).isEqualTo(known(16_000_000L));
        assertThat(result.pnl().total().feesLamports()).isEqualTo(8_000L);
        assertThat(result.pnl().total().total()).isEqualTo(known(110_992_000L));
        assertMatch(result);
    }

    /** AC5.4.1, AC5.4.2: the realized of [2026-11-10, 2026-11-17) is +125M; since the beginning +165M. */
    @Test
    void ac541And542TheRealizedOfAPeriodAndSinceTheBeginning() {
        PnlScenario ledger = abc();
        ledger.at("2026-11-01T10:00:00Z").buy(A, 200, 200_000_000L, 0);
        ledger.at("2026-11-02T10:00:00Z").purgedBuy(10, 15_000_000L);
        ledger.at("2026-11-03T10:00:00Z").buy(B, 30, 30_000_000L, 0);
        ledger.at("2026-11-05T14:00:00Z").ownSale(A, 100, 140_000_000L, 0);
        ledger.at("2026-11-12T09:00:00Z").ownSale(A, 100, 200_000_000L, 0);
        ledger.at("2026-11-14T16:00:00Z").ownSale(B, 40, 80_000_000L, 0);
        ledger.at("2026-11-14T17:00:00Z").buy(C, 100, 110_000_000L, 0);
        ledger.at("2026-11-15T11:00:00Z").liquidation(100, 100_000_000L, 0);

        PnlWindow week =
                new PnlWindow.Range(Instant.parse("2026-11-10T00:00:00Z"), Instant.parse("2026-11-17T00:00:00Z"));
        RealPnlResult period = ledger.run(week, Map.of());
        RealPnlResult all = ledger.all();

        assertThat(line(period, A).realizedLamports()).isEqualTo(100_000_000L);
        assertThat(line(period, B).realizedLamports()).isEqualTo(30_000_000L);
        assertThat(line(period, C).realizedLamports()).isEqualTo(-10_000_000L);
        assertThat(period.pnl().unowned().orElseThrow().realizedLamports()).isEqualTo(5_000_000L);
        assertThat(period.pnl().total().realizedLamports()).isEqualTo(125_000_000L);
        assertThat(line(all, A).realizedLamports()).isEqualTo(140_000_000L);
        assertThat(all.pnl().total().realizedLamports()).isEqualTo(165_000_000L);
        assertMatch(period);
        assertMatch(all);
    }

    /** FR5.4: a sale in the same slot with rows without a recorded slot (SLOT_MISSING) follows the recording order. */
    @Test
    void fr54ASaleInTheSameSlotWithSlotMissingFollowsTheRecordingOrder() {
        PnlScenario ledger = abc();
        ledger.buy(A, 100, 100_000_000L, 0);
        ledger.slotMissing();
        ledger.buy(B, 100, 300_000_000L, 5_000L);
        ledger.ownSale(A, 200, 400_000_000L, 5_000L);

        RealPnlResult result = ledger.all();

        assertThat(line(result, A).realizedLamports()).isEqualTo(100_000_000L);
        assertThat(line(result, B).realizedLamports()).isEqualTo(-100_000_000L);
        assertThat(line(result, B).feesLamports()).isEqualTo(7_500L);
        assertMatch(result);
    }

    /** FR5.4, Q3: an excess without cost: A sells 12 of its 10; "Sem bot" receives 200 with UNOWNED_NO_COST. */
    @Test
    void fr54AnExcessWithoutCostGoesToTheLineWithoutBot() {
        PnlScenario ledger = abc();
        ledger.buy(A, 10, 1_000L, 0);
        ledger.ownSale(A, 12, 1_200L, 0);

        RealPnlResult result = ledger.all();

        assertThat(line(result, A).realizedLamports()).isZero();
        UnownedPnlLine unowned = result.pnl().unowned().orElseThrow();
        assertThat(unowned.realizedLamports()).isEqualTo(200L);
        assertThat(unowned.reasons()).containsExactly("UNOWNED_NO_COST");
        assertThat(result.positions()).isEmpty();
        assertMatch(result);
    }
}
