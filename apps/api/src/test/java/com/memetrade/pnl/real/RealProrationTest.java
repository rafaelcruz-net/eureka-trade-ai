package com.memetrade.pnl.real;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.ledger.api.CostHolder;
import com.memetrade.pnl.real.ProrationPart.Origin;
import com.memetrade.pnl.real.RealProration.Holding;
import com.memetrade.pnl.real.RealProration.QuantityPart;
import com.memetrade.pnl.real.RealProration.SaleKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U2, Etapa 16 (WRITTEN BEFORE THE CODE; BR4.1 to BR4.8, BR5.2, BR5.3, BR8.4, decisions Q2,
 * Q3, D2): the largest remainder of every sale, in base units and lamports. The quantity of a sale without bot is
 * shared by the quantity just before it; an own sale gives first the quantity of its seller and shares the excess among
 * every OTHER holder, the part without owner included; what passes every recorded position belongs to UNOWNED without
 * cost. The received lamports and the fee are shared by the quantity each participant sold, with the tie-break of BR4.2
 * (older bot, then smaller id, UNOWNED last). In every event the parts sum the event.
 *
 * <p>Bots of the examples (stories.md): A created 2026-11-01T10:00Z; B and C 2026-11-02T10:00Z, the id of B smaller.
 */
class RealProrationTest {

    private static final Holder A = RealCostBasisTest.A;
    private static final Holder B = RealCostBasisTest.B;
    private static final Holder C = RealCostBasisTest.C;
    private static final Holder UNOWNED = Holder.UNOWNED;

    private static Holding held(CostHolder holder, long units, long cost) {
        return new Holding(holder, units, cost);
    }

    private static ProrationPart part(
            CostHolder holder, long units, long received, long fee, long cost, long realized, Origin origin) {
        return new ProrationPart(holder, units, received, fee, cost, realized, origin);
    }

    /** AC3.3.1, AC3.3.3: a liquidation of 400 over A 100 (200M) and B 300 (900M), 1.2B received, fee 10.000. */
    @Test
    void ac331ALiquidationWithTwoBotsSharesQuantityReceivedAndFeeByTheQuantityBeforeIt() {
        List<ProrationPart> parts = RealProration.sale(
                SaleKind.LIQUIDATION,
                Optional.empty(),
                List.of(held(A, 100, 200_000_000L), held(B, 300, 900_000_000L)),
                400,
                1_200_000_000L,
                10_000L);

        assertThat(parts)
                .containsExactly(
                        part(A, 100, 300_000_000L, 2_500L, 200_000_000L, 100_000_000L, Origin.LIQUIDATION),
                        part(B, 300, 900_000_000L, 7_500L, 900_000_000L, 0L, Origin.LIQUIDATION));
        assertThat(parts.stream().mapToLong(ProrationPart::realizedLamports).sum())
                .isEqualTo(1_200_000_000L - 1_100_000_000L);
    }

    /** AC3.3.2: three bots with a remainder: A 388.888.889, B 233.333.333, C 155.555.555; fees 3.500, 2.100, 1.400. */
    @Test
    void ac332ThreeBotsTheLamportLeftGoesToTheLargestRemainder() {
        List<ProrationPart> parts = RealProration.sale(
                SaleKind.LIQUIDATION,
                Optional.empty(),
                List.of(held(A, 50, 400_000_000L), held(B, 30, 210_000_000L), held(C, 20, 160_000_000L)),
                100,
                777_777_777L,
                7_000L);

        assertThat(parts)
                .containsExactly(
                        part(A, 50, 388_888_889L, 3_500L, 400_000_000L, -11_111_111L, Origin.LIQUIDATION),
                        part(B, 30, 233_333_333L, 2_100L, 210_000_000L, 23_333_333L, Origin.LIQUIDATION),
                        part(C, 20, 155_555_555L, 1_400L, 160_000_000L, -4_444_445L, Origin.LIQUIDATION));
        assertThat(parts.stream().mapToLong(ProrationPart::realizedLamports).sum())
                .isEqualTo(7_777_777L);
    }

    /** AC3.3.4, BR4.8: a holder with no quantity just before the sale gets no part, not even of the remainder. */
    @Test
    void ac334AHolderWithoutQuantityBeforeTheSaleGetsNoPart() {
        List<ProrationPart> parts = RealProration.sale(
                SaleKind.LIQUIDATION,
                Optional.empty(),
                List.of(held(A, 100, 100L), held(B, 0, 0L), held(C, 200, 200L)),
                300,
                1_000L,
                1L);

        assertThat(parts).extracting(ProrationPart::participant).containsExactly(A, C);
        assertThat(parts).extracting(ProrationPart::feeLamports).containsExactly(0L, 1L);
    }

    /** AC3.4.1, AC3.4.3: equal remainders: A 333.333.334 and fees A 1.667, B 1.667, C 1.666; origin CONDITIONAL. */
    @Test
    void ac341AConditionalWithATieGivesTheUnitsLeftInTheOrderOfTheTieBreak() {
        List<ProrationPart> parts = RealProration.sale(
                SaleKind.CONDITIONAL,
                Optional.empty(),
                List.of(held(A, 100, 300_000_000L), held(B, 100, 450_000_000L), held(C, 100, 600_000_000L)),
                300,
                1_000_000_000L,
                5_000L);

        assertThat(parts)
                .containsExactly(
                        part(A, 100, 333_333_334L, 1_667L, 300_000_000L, 33_333_334L, Origin.CONDITIONAL),
                        part(B, 100, 333_333_333L, 1_667L, 450_000_000L, -116_666_667L, Origin.CONDITIONAL),
                        part(C, 100, 333_333_333L, 1_666L, 600_000_000L, -266_666_667L, Origin.CONDITIONAL));
    }

    /** AC3.4.2, AC3.4.4, BR4.6: a partial conditional only shares what it executed; the rest gives no part at all. */
    @Test
    void ac342And344APartialConditionalSharesOnlyTheExecutedQuantity() {
        List<ProrationPart> parts = RealProration.sale(
                SaleKind.CONDITIONAL,
                Optional.empty(),
                List.of(held(A, 600, 1_200_000_000L), held(B, 400, 1_000_000_000L)),
                500,
                1_500_000_000L,
                5_000L);

        assertThat(parts)
                .containsExactly(
                        part(A, 300, 900_000_000L, 3_000L, 600_000_000L, 300_000_000L, Origin.CONDITIONAL),
                        part(B, 200, 600_000_000L, 2_000L, 500_000_000L, 100_000_000L, Origin.CONDITIONAL));
        assertThat(parts.stream().mapToLong(ProrationPart::tokenBaseUnits).sum())
                .isEqualTo(500L);
    }

    /** AC3.5.1, BR4.4, BR4.5: A sells 400; its own 100, the excess of 300 to B and C by 200 : 100. */
    @Test
    void ac351TheExcessOfAnOwnSaleGoesToTheOtherBotsAsSoldByOtherBot() {
        List<ProrationPart> parts = RealProration.sale(
                SaleKind.OWN_SALE,
                Optional.of(A),
                List.of(held(A, 100, 100_000_000L), held(B, 200, 300_000_000L), held(C, 100, 100_000_000L)),
                400,
                800_000_000L,
                4_000L);

        assertThat(parts)
                .containsExactly(
                        part(A, 100, 200_000_000L, 1_000L, 100_000_000L, 100_000_000L, Origin.OWN),
                        part(B, 200, 400_000_000L, 2_000L, 300_000_000L, 100_000_000L, Origin.SOLD_BY_OTHER_BOT),
                        part(C, 100, 200_000_000L, 1_000L, 100_000_000L, 100_000_000L, Origin.SOLD_BY_OTHER_BOT));
    }

    /** AC3.5.2, BR5.1, BR5.2: the excess goes to the registered quantity of UNOWNED, with UNOWNED_EXCESS. */
    @Test
    void ac352WithoutAnotherBotTheExcessGoesToTheRegisteredPartWithoutOwner() {
        List<ProrationPart> parts = RealProration.sale(
                SaleKind.OWN_SALE,
                Optional.of(A),
                List.of(held(A, 100, 100_000_000L), held(UNOWNED, 50, 75_000_000L)),
                150,
                300_000_000L,
                3_000L);

        assertThat(parts)
                .containsExactly(
                        part(A, 100, 200_000_000L, 2_000L, 100_000_000L, 100_000_000L, Origin.OWN),
                        part(UNOWNED, 50, 100_000_000L, 1_000L, 75_000_000L, 25_000_000L, Origin.UNOWNED_EXCESS));
    }

    /** AC3.5.3: a bot that sells at most what it holds gives no part to another holder. */
    @Test
    void ac353AnOwnSaleWithinTheSellerHasOnlyTheOwnPart() {
        List<ProrationPart> parts = RealProration.sale(
                SaleKind.OWN_SALE,
                Optional.of(A),
                List.of(held(A, 100, 100L), held(B, 200, 300L), held(UNOWNED, 10, 10L)),
                100,
                500L,
                5L);

        assertThat(parts).containsExactly(part(A, 100, 500L, 5L, 100L, 400L, Origin.OWN));
    }

    /** AC3.6.1: after the buy of B in the same slot, A sells 200: its 100 and the excess of 100 to B. */
    @Test
    void ac361TheBuyOfTheSameSlotFirstThenTheExcessOfTheSaleGoesToTheBuyer() {
        List<ProrationPart> parts = RealProration.sale(
                SaleKind.OWN_SALE,
                Optional.of(A),
                List.of(held(A, 100, 100_000_000L), held(B, 100, 300_000_000L)),
                200,
                400_000_000L,
                5_000L);

        assertThat(parts)
                .containsExactly(
                        part(A, 100, 200_000_000L, 2_500L, 100_000_000L, 100_000_000L, Origin.OWN),
                        part(B, 100, 200_000_000L, 2_500L, 300_000_000L, -100_000_000L, Origin.SOLD_BY_OTHER_BOT));
    }

    /** Q2: A 2 and B 1, a conditional of 2: integer parts A 1 (remainder 1) and B 0 (remainder 2): A 1, B 1. */
    @Test
    void q2TheQuantityGoesByTheLargestRemainderAndTheMoneyByTheQuantitySold() {
        List<QuantityPart> quantities =
                RealProration.split(Optional.empty(), 2, List.of(held(A, 2, 20L), held(B, 1, 10L)));

        assertThat(quantities).containsExactly(new QuantityPart(A, 1, false), new QuantityPart(B, 1, false));
        List<ProrationPart> parts = RealProration.sale(
                SaleKind.CONDITIONAL, Optional.empty(), List.of(held(A, 2, 20L), held(B, 1, 10L)), 2, 101L, 3L);
        assertThat(parts).extracting(ProrationPart::receivedLamports).containsExactly(51L, 50L);
        assertThat(parts).extracting(ProrationPart::feeLamports).containsExactly(2L, 1L);
    }

    /** Q3, BR5.3: A has 10 and sells 12 for 1.200: A 1.000; 2 go to UNOWNED without cost, realized +200. */
    @Test
    void q3TheQuantityBeyondEveryPositionGoesToUnownedWithoutCost() {
        List<ProrationPart> parts =
                RealProration.sale(SaleKind.OWN_SALE, Optional.of(A), List.of(held(A, 10, 1_000L)), 12, 1_200L, 0L);

        assertThat(parts)
                .containsExactly(
                        part(A, 10, 1_000L, 0L, 1_000L, 0L, Origin.OWN),
                        part(UNOWNED, 2, 200L, 0L, 0L, 200L, Origin.UNOWNED_NO_COST));
    }

    /** D2: A 100, B 50 and UNOWNED 50 (cost 60); A sells 150 for 1.500: B 25 and UNOWNED 25; "Sem bot" +220. */
    @Test
    void d2TheExcessIsSharedAmongEveryOtherHolderThePartWithoutOwnerIncluded() {
        List<ProrationPart> parts = RealProration.sale(
                SaleKind.OWN_SALE,
                Optional.of(A),
                List.of(held(A, 100, 700L), held(B, 50, 400L), held(UNOWNED, 50, 60L)),
                150,
                1_500L,
                0L);

        assertThat(parts)
                .containsExactly(
                        part(A, 100, 1_000L, 0L, 700L, 300L, Origin.OWN),
                        part(B, 25, 250L, 0L, 200L, 50L, Origin.SOLD_BY_OTHER_BOT),
                        part(UNOWNED, 25, 250L, 0L, 30L, 220L, Origin.UNOWNED_EXCESS));
    }

    /** BR3.2, BR5.4: an order gone makes UNOWNED the seller; its own part is OWN, the excess goes to the bots. */
    @Test
    void anUnownedSellerSellsItsRegisteredQuantityFirstAndTheExcessGoesToTheBots() {
        List<ProrationPart> parts = RealProration.sale(
                SaleKind.OWN_SALE,
                Optional.of(UNOWNED),
                List.of(held(A, 10, 100L), held(UNOWNED, 10, 50L)),
                25,
                250L,
                5L);

        assertThat(parts)
                .containsExactly(
                        part(A, 10, 100L, 2L, 100L, 0L, Origin.SOLD_BY_OTHER_BOT),
                        part(UNOWNED, 10, 100L, 2L, 50L, 50L, Origin.OWN),
                        part(UNOWNED, 5, 50L, 1L, 0L, 50L, Origin.UNOWNED_NO_COST));
    }

    /** BR6.2: the fee of a failed liquidation is shared by the quantities before it; nobody, nothing to share. */
    @Test
    void theFeeOfAFailedLiquidationIsSharedByTheQuantitiesBeforeItWithNothingElse() {
        List<ProrationPart> parts = RealProration.fee(
                SaleKind.LIQUIDATION, List.of(held(A, 100, 1L), held(B, 300, 1L), held(C, 0, 0L)), 5_000L);

        assertThat(parts)
                .containsExactly(
                        part(A, 0, 0L, 1_250L, 0L, 0L, Origin.LIQUIDATION),
                        part(B, 0, 0L, 3_750L, 0L, 0L, Origin.LIQUIDATION));
        assertThat(RealProration.fee(SaleKind.LIQUIDATION, List.of(), 5_000L)).isEmpty();
    }

    /** BR2.2 (Q1): the cost removed is all the cost at zero, otherwise rounded up. */
    @Test
    void theCostRemovedIsRoundedUpAndTheLastSaleTakesWhatRemains() {
        assertThat(RealProration.costRemoved(3, 10, 1)).isEqualTo(4L);
        assertThat(RealProration.costRemoved(2, 6, 1)).isEqualTo(3L);
        assertThat(RealProration.costRemoved(1, 3, 1)).isEqualTo(3L);
        assertThat(RealProration.costRemoved(200, 400_000_000L, 50)).isEqualTo(100_000_000L);
    }

    /** BR8.4: over many sales of a fixed seed, the parts always sum the quantity, the received and the fee. */
    @Test
    void inEveryEventThePartsSumTheEvent() {
        long seed = 20261123L;
        Random random = new Random(seed);
        List<Holder> holders = List.of(A, B, C, UNOWNED);
        for (int i = 0; i < 2_000; i++) {
            List<Holding> before = new ArrayList<>();
            for (Holder holder : holders) {
                long units = random.nextInt(4) == 0 ? 0 : 1 + random.nextInt(1_000);
                before.add(held(holder, units, units == 0 ? 0 : random.nextInt(1_000_000_000)));
            }
            long sold = 1 + random.nextInt(4_000);
            long received = random.nextInt(2_000_000_000);
            long fee = random.nextInt(100_000);
            SaleKind kind = SaleKind.values()[random.nextInt(3)];
            Optional<CostHolder> seller =
                    kind == SaleKind.OWN_SALE ? Optional.of(holders.get(random.nextInt(4))) : Optional.empty();

            List<ProrationPart> parts = RealProration.sale(kind, seller, before, sold, received, fee);

            String where = "seed " + seed + ", sale " + i;
            assertThat(parts.stream().mapToLong(ProrationPart::tokenBaseUnits).sum())
                    .as(where)
                    .isEqualTo(sold);
            assertThat(parts.stream().mapToLong(ProrationPart::receivedLamports).sum())
                    .as(where)
                    .isEqualTo(received);
            assertThat(parts.stream().mapToLong(ProrationPart::feeLamports).sum())
                    .as(where)
                    .isEqualTo(fee);
            assertThat(parts).as(where).allSatisfy(p -> assertThat(p.realizedLamports())
                    .isEqualTo(p.receivedLamports() - p.costRemovedLamports()));
            for (ProrationPart p : parts) {
                long had = before.stream()
                        .filter(h -> h.holder().equals(p.participant()))
                        .mapToLong(Holding::units)
                        .sum();
                if (p.origin() != Origin.UNOWNED_NO_COST) {
                    assertThat(p.tokenBaseUnits()).as(where).isBetween(1L, had);
                }
            }
        }
    }
}
