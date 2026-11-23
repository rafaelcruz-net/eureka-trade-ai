package com.memetrade.pnl.real;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.ledger.api.CostFill;
import com.memetrade.ledger.api.CostHolder;
import com.memetrade.ledger.api.NetworkOrderKey;
import com.memetrade.ledger.api.RealCost;
import com.memetrade.ledger.api.RealCostMethod;
import com.memetrade.ledger.api.RealCostMethod.CostPart;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealPosition;
import com.memetrade.ledger.domain.RealPositions;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U2, Etapa 10 (written before the code; BR9.1, BR2.4, BR2.5, AC5.2.1, AC5.2.2, decision D1,
 * NFR2.8): the wallet is the sum of the bots plus the part without owner, and the P&L and the barrier use the same
 * numbers. Checked after EVERY event and at the end, with a difference tolerated of 0, against three oracles written
 * here, without {@code RealCostBasis} nor {@code RealProration}:
 *
 * <ol>
 *   <li>quantity, raw oracle: the raw swap fills in network order, {@code current += IN} and
 *       {@code current -= min(OUT, current)}; equal to the quantity-only path of the ledger ({@code RealPositions},
 *       what {@code positions()} and {@code view()} read), to the sum of the holders and, without an over-sale, to the
 *       token balance of the ledger (sum of IN minus OUT);
 *   <li>cost, conservation oracle: the raw cost of the buys equals every cost removed plus the open cost of the holders;
 *   <li>cost per holder, recompute oracle: for each holder, its buys and the quantities attributed to it, with the rule
 *       of Q1 written again here, give the cost of the accumulator.
 * </ol>
 *
 * Item 4 of BR9.1 (the read of cost brings the sum of the holders) is the open positions of the wallet compared with the
 * sum of the holders.
 */
class RealCostParityTest {

    private static final Holder A = RealCostBasisTest.A;
    private static final Holder B = RealCostBasisTest.B;
    private static final Holder C = RealCostBasisTest.C;
    private static final Holder UNOWNED = Holder.UNOWNED;
    private static final List<Holder> HOLDERS = List.of(A, B, C, UNOWNED);

    private sealed interface Step permits Buy, OwnSale, SaleWithoutBot {}

    private record Buy(Holder holder, String mint, long units, long paid) implements Step {}

    private record OwnSale(Holder seller, String mint, long units, long received) implements Step {}

    private record SaleWithoutBot(String mint, long units, long received) implements Step {}

    /** AC5.2.1: AC3.2.1 and AC3.2.2: 150 XYZ with a cost of 300.000.000 in the P&L and in the positions. */
    @Test
    void ac521ThePnlAndThePositionsShowTheSameQuantityAndCost() {
        Run run = run(List.of(
                new Buy(A, "XYZ", 100, 100_000_000L),
                new Buy(A, "XYZ", 100, 300_000_000L),
                new OwnSale(A, "XYZ", 50, 150_000_000L)));

        assertThat(run.accumulator.openPositions())
                .containsExactly(new RealPosition("XYZ", 150, new RealCost.Known(300_000_000L)));
        assertThat(RealPositions.of(run.fills))
                .extracting(RealPosition::tokenBaseUnits)
                .containsExactly(150L);
    }

    /** AC5.2.2: buys, sales, a liquidation and a partial conditional: quantity and cost equal lamport by lamport. */
    @Test
    void ac522BuysSalesALiquidationAndAConditionalKeepTheParity() {
        run(List.of(
                new Buy(A, "XYZ", 600, 1_200_000_000L),
                new Buy(B, "XYZ", 400, 1_000_000_000L),
                new Buy(C, "ABC", 70, 7_777_777L),
                new OwnSale(B, "XYZ", 150, 400_000_000L),
                new SaleWithoutBot("XYZ", 500, 1_500_000_000L),
                new Buy(UNOWNED, "XYZ", 33, 1_000_003L),
                new SaleWithoutBot("ABC", 70, 9_000_000L),
                new OwnSale(A, "XYZ", 400, 1_000_000_000L)));
    }

    /**
     * Decision D1: A holds 100 for 100.000.000, B 100 for 300.000.000, and A sells its own 100. A removes 100.000.000;
     * an average cost of the whole wallet would remove 200.000.000 and diverge from the sum of the bots.
     */
    @Test
    void ownSalesOfBotsWithDifferentAverageCostsKeepTheWalletTheSumOfTheBots() {
        Run run = run(List.of(
                new Buy(A, "XYZ", 100, 100_000_000L),
                new Buy(B, "XYZ", 100, 300_000_000L),
                new OwnSale(A, "XYZ", 100, 250_000_000L)));

        assertThat(run.removed).isEqualTo(100_000_000L);
        assertThat(run.accumulator.openPositions())
                .containsExactly(new RealPosition("XYZ", 100, new RealCost.Known(300_000_000L)));
    }

    /** Over-sales, excesses and partial sales in a long sequence with a fixed seed keep every oracle exact. */
    @Test
    void aLongMixedSequenceWithAFixedSeedKeepsEveryOracleExact() {
        long seed = 20261122L;
        Random random = new Random(seed);
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            String mint = random.nextBoolean() ? "XYZ" : "ABC";
            Holder holder = HOLDERS.get(random.nextInt(HOLDERS.size()));
            long units = 1 + random.nextInt(500);
            switch (random.nextInt(3)) {
                case 0 -> steps.add(new Buy(holder, mint, units, 1 + random.nextInt(1_000_000_007)));
                case 1 -> steps.add(new OwnSale(holder, mint, units, random.nextInt(1_000_000)));
                default -> steps.add(new SaleWithoutBot(mint, units, random.nextInt(1_000_000)));
            }
        }

        run(steps);
    }

    /**
     * 261121-paper-real-unico U2a, Etapa 14a (written before the code): a fourth oracle. The cost read only from the
     * epoch of each mint equals the cost read from the whole history, after every event, over at least 400 events of a
     * fixed seed (buys with RENT, own sales with excess, sales without bot, conditionals, rows without a slot).
     */
    @Test
    void fourthOracleTheCostFromTheEpochIsTheCostOfTheWholeHistory() {
        Random random = new Random(20261122L);
        RealCostBasisEpochTest.Ledger ledger = new RealCostBasisEpochTest.Ledger();
        for (int i = 0; i < 400; i++) {
            String mint = random.nextBoolean() ? "XYZ" : "ABC";
            Holder holder = HOLDERS.get(random.nextInt(HOLDERS.size()));
            long units = 1 + random.nextInt(500);
            switch (random.nextInt(3)) {
                case 0 ->
                    ledger.buy(
                            holder,
                            mint,
                            units,
                            1 + random.nextInt(1_000_000_007),
                            random.nextInt(2_039_281),
                            random.nextBoolean());
                case 1 -> ledger.ownSale(holder, mint, units, random.nextInt(1_000_000));
                default -> ledger.saleWithoutBot(mint, units, random.nextInt(1_000_000), random.nextBoolean());
            }
            ledger.assertTheEpochGivesTheCostOfTheWholeHistory();
        }
    }

    /**
     * 261121-paper-real-unico U2a, Etapa 14b (written before the code): a fifth oracle. After every transaction the
     * writer keeps the marks from a read restricted by them (the rows of each mint after its mark, checked by the count
     * of its fills; a full read when the count disagrees), and the latest mark of each mint is the zero point found by
     * the recompute of the whole history; the open lots and the quantities of the restricted read are those of the whole
     * history. With a fixed seed, at least 400 events, rows without a recorded slot and late confirmations whose slot
     * lands before the latest mark.
     */
    @Test
    void fifthOracleTheMarkIsTheZeroPointOfTheFullRecompute() {
        Random random = new Random(20261123L);
        MarkedLedger ledger = new MarkedLedger();
        for (int i = 0; i < 400; i++) {
            String mint = random.nextBoolean() ? "XYZ" : "ABC";
            long units = 1 + random.nextInt(300);
            boolean late = random.nextInt(7) == 0;
            boolean inherited = random.nextInt(5) == 0;
            if (random.nextInt(5) < 3) {
                ledger.buy(mint, units, random.nextBoolean() ? 2_039_280 : 0, late, inherited);
            } else {
                ledger.sell(mint, units, late, inherited);
            }
            ledger.writerKeepsTheMarks();
            ledger.assertTheMarksAreTheZeroPointsOfTheFullRecompute("after event " + i);
        }
        assertThat(ledger.fullReads).as("late confirmations forced full reads").isPositive();
        assertThat(ledger.restrictedReads).as("most reads were restricted").isGreaterThan(ledger.fullReads);
    }

    /** The rows of one wallet with keys given at write time (a late confirmation takes an older slot). */
    private static final class MarkedLedger {
        final List<com.memetrade.ledger.api.NetworkOrderedEntry> rows = new ArrayList<>();
        final Map<String, com.memetrade.ledger.domain.EpochMark> marks = new HashMap<>();
        final Random slots = new Random(7L);
        long slot = 1_000;
        long written;
        int fullReads;
        int restrictedReads;

        void buy(String mint, long units, long rent, boolean late, boolean inherited) {
            String reference = "order:" + UUID.randomUUID();
            long at = slotOf(late);
            add(RealEntryKind.SWAP_FILL_IN, mint, units, -1_000_000L, reference, at, 0, inherited);
            if (rent > 0) {
                add(RealEntryKind.RENT, null, 0, -rent, reference, at, 2, inherited);
            }
        }

        void sell(String mint, long units, boolean late, boolean inherited) {
            add(
                    RealEntryKind.SWAP_FILL_OUT,
                    mint,
                    units,
                    700_000L,
                    "order:" + UUID.randomUUID(),
                    slotOf(late),
                    0,
                    inherited);
        }

        private long slotOf(boolean late) {
            slot += 10;
            return late ? Math.max(0, slot - 10 - slots.nextInt(200)) : slot;
        }

        private void add(
                RealEntryKind kind,
                String mint,
                long units,
                long lamports,
                String reference,
                long at,
                int rank,
                boolean inherited) {
            written++;
            Instant createdAt = Instant.parse("2026-11-01T00:00:00Z").plusSeconds(written);
            Optional<String> signature = inherited ? Optional.empty() : Optional.of("Sig" + reference);
            NetworkOrderKey key =
                    new NetworkOrderKey(at, inherited, signature, createdAt, reference, rank, reference + ":" + kind);
            rows.add(new com.memetrade.ledger.api.NetworkOrderedEntry(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    kind,
                    lamports,
                    Optional.ofNullable(mint),
                    kind == RealEntryKind.RENT ? Optional.empty() : Optional.of(units),
                    signature,
                    reference,
                    createdAt,
                    key,
                    inherited
                            ? com.memetrade.ledger.api.SlotStatus.SLOT_MISSING
                            : com.memetrade.ledger.api.SlotStatus.RECORDED));
        }

        private List<com.memetrade.ledger.api.NetworkOrderedEntry> sorted() {
            return rows.stream()
                    .sorted(java.util.Comparator.comparing(com.memetrade.ledger.api.NetworkOrderedEntry::key))
                    .toList();
        }

        /** Oracle of the read restricted by the marks, written here: rows after each mark and their RENT. */
        private List<com.memetrade.ledger.api.NetworkOrderedEntry> restricted() {
            java.util.Set<String> buys = new java.util.HashSet<>();
            List<com.memetrade.ledger.api.NetworkOrderedEntry> read = new ArrayList<>();
            for (com.memetrade.ledger.api.NetworkOrderedEntry row : sorted()) {
                if (row.kind() == RealEntryKind.RENT) {
                    if (buys.contains(row.reference())) {
                        read.add(row);
                    }
                    continue;
                }
                com.memetrade.ledger.domain.EpochMark mark =
                        marks.get(row.mint().orElseThrow());
                boolean after = mark == null
                        || mark.zero().isEmpty()
                        || row.key().compareTo(mark.zero().get()) > 0;
                if (after) {
                    read.add(row);
                    if (row.kind() == RealEntryKind.SWAP_FILL_IN) {
                        buys.add(row.reference());
                    }
                }
            }
            return read;
        }

        /** The marks whose count of fills disagrees with the rows: a row landed before the mark. */
        private java.util.Set<String> invalid(List<com.memetrade.ledger.api.NetworkOrderedEntry> read) {
            java.util.Set<String> invalid = new java.util.HashSet<>();
            for (String mint : List.of("XYZ", "ABC")) {
                long total = rows.stream()
                        .filter(r -> r.kind() != RealEntryKind.RENT
                                && r.mint().orElseThrow().equals(mint))
                        .count();
                long after = read.stream()
                        .filter(r -> r.kind() != RealEntryKind.RENT
                                && r.mint().orElseThrow().equals(mint))
                        .count();
                com.memetrade.ledger.domain.EpochMark mark = marks.get(mint);
                long through = mark == null ? 0 : mark.fillsThrough();
                if (total != through + after) {
                    invalid.add(mint);
                }
            }
            return invalid;
        }

        void writerKeepsTheMarks() {
            List<com.memetrade.ledger.api.NetworkOrderedEntry> read = restricted();
            java.util.Set<String> invalid = invalid(read);
            if (!invalid.isEmpty()) {
                fullReads++;
                read = sorted();
            } else {
                restrictedReads++;
            }
            com.memetrade.ledger.domain.RealEpochs epochs = new com.memetrade.ledger.domain.RealEpochs();
            read.forEach(epochs::accept);
            Instant now = Instant.parse("2026-11-01T00:00:00Z").plusSeconds(written);
            for (com.memetrade.ledger.domain.EpochMark mark : com.memetrade.ledger.domain.RealEpochMarks.plan(
                    Map.copyOf(marks), epochs, invalid, Optional.empty(), now)) {
                marks.put(mark.mint(), mark);
            }
        }

        void assertTheMarksAreTheZeroPointsOfTheFullRecompute(String when) {
            com.memetrade.ledger.domain.RealEpochs full = new com.memetrade.ledger.domain.RealEpochs();
            sorted().forEach(full::accept);
            for (String mint : List.of("XYZ", "ABC")) {
                Optional<com.memetrade.ledger.domain.RealEpochs.ZeroPoint> zero = full.zeroPoint(mint);
                com.memetrade.ledger.domain.EpochMark mark = marks.get(mint);
                if (zero.isPresent()) {
                    assertThat(mark)
                            .as("a mark for the zero of %s %s", mint, when)
                            .isNotNull();
                    assertThat(mark.zero()).as("the zero of %s %s", mint, when).isPresent();
                    assertThat(mark.zero().get().compareTo(zero.get().key()))
                            .as("the mark of %s is the zero of the full recompute %s", mint, when)
                            .isZero();
                    assertThat(mark.fillsThrough())
                            .as("the fills through the zero of %s %s", mint, when)
                            .isEqualTo(zero.get().fillsThrough());
                } else if (mark != null) {
                    assertThat(mark.zero())
                            .as("no zero of %s means a full-read mark %s", mint, when)
                            .isEmpty();
                }
            }
            com.memetrade.ledger.domain.RealEpochs restricted = new com.memetrade.ledger.domain.RealEpochs();
            restricted().forEach(restricted::accept);
            assertThat(restricted.openLots()).as("the open lots %s", when).isEqualTo(full.openLots());
            assertThat(restricted.quantities().positions())
                    .as("the quantities %s", when)
                    .containsExactlyInAnyOrderElementsOf(full.quantities().positions());
        }
    }

    // ----- the run and the three oracles
    // ------------------------------------------------------------------------------

    private static final class Run {
        final RealCostMethod.Accumulator accumulator = new RealCostBasis().start();
        final List<RealPositions.Fill> fills = new ArrayList<>();
        final Map<String, Long> rawQuantity = new HashMap<>();
        final Map<String, Long> boughtUnits = new HashMap<>();
        final Map<String, Long> soldUnits = new HashMap<>();
        final Map<String, Long> rawCost = new HashMap<>();
        final Map<String, Long> removedByMint = new HashMap<>();
        final Map<String, long[]> recomputed = new HashMap<>();
        boolean overSold;
        long removed;
        long slot;
    }

    private static Run run(List<Step> steps) {
        Run run = new Run();
        for (int i = 0; i < steps.size(); i++) {
            apply(run, steps.get(i));
            assertParity(run, "after event " + i + " " + steps.get(i));
        }
        assertParity(run, "at the end");
        return run;
    }

    private static void apply(Run run, Step step) {
        NetworkOrderKey key = new NetworkOrderKey(
                ++run.slot,
                false,
                Optional.of("Sig" + run.slot),
                Instant.EPOCH,
                "order:" + run.slot,
                0,
                "k" + run.slot);
        switch (step) {
            case Buy buy -> {
                run.accumulator.apply(new CostFill(
                        key,
                        RealEntryKind.SWAP_FILL_IN,
                        buy.mint(),
                        buy.units(),
                        -buy.paid(),
                        Optional.of(buy.holder())));
                run.fills.add(new RealPositions.Fill(RealEntryKind.SWAP_FILL_IN, -buy.paid(), buy.mint(), buy.units()));
                run.rawQuantity.merge(buy.mint(), buy.units(), Math::addExact);
                run.boughtUnits.merge(buy.mint(), buy.units(), Math::addExact);
                run.rawCost.merge(buy.mint(), buy.paid(), Math::addExact);
                long[] position = run.recomputed.computeIfAbsent(holderKey(buy.holder(), buy.mint()), k -> new long[2]);
                position[0] = Math.addExact(position[0], buy.units());
                position[1] = Math.addExact(position[1], buy.paid());
            }
            case OwnSale sale -> sell(run, key, sale.mint(), sale.units(), sale.received(), Optional.of(sale.seller()));
            case SaleWithoutBot sale -> sell(run, key, sale.mint(), sale.units(), sale.received(), Optional.empty());
        }
    }

    private static void sell(
            Run run, NetworkOrderKey key, String mint, long units, long received, Optional<CostHolder> seller) {
        List<CostPart> parts =
                run.accumulator.apply(new CostFill(key, RealEntryKind.SWAP_FILL_OUT, mint, units, received, seller));
        run.fills.add(new RealPositions.Fill(RealEntryKind.SWAP_FILL_OUT, received, mint, units));
        long current = run.rawQuantity.getOrDefault(mint, 0L);
        run.overSold |= units > current;
        run.rawQuantity.put(mint, current - Math.min(units, current));
        run.soldUnits.merge(mint, units, Math::addExact);
        assertThat(parts.stream().mapToLong(CostPart::tokenBaseUnits).sum())
                .as("the parts sum the quantity sold")
                .isEqualTo(units);
        for (CostPart part : parts) {
            run.removed = Math.addExact(run.removed, part.costRemovedLamports());
            run.removedByMint.merge(mint, part.costRemovedLamports(), Math::addExact);
            if (part.withoutCost()) {
                continue;
            }
            // Oracle 3: the rule of Q1 written again: all of it at zero, otherwise ceil(c x s / q).
            long[] position = run.recomputed.get(holderKey((Holder) part.holder(), mint));
            assertThat(position).as("a holder that gives quantity holds it").isNotNull();
            long q = position[0];
            long c = position[1];
            long s = part.tokenBaseUnits();
            long expectedRemoved = s == q
                    ? c
                    : BigInteger.valueOf(c)
                            .multiply(BigInteger.valueOf(s))
                            .add(BigInteger.valueOf(q - 1))
                            .divide(BigInteger.valueOf(q))
                            .longValueExact();
            assertThat(part.costRemovedLamports())
                    .as("cost removed from %s", part.holder())
                    .isEqualTo(expectedRemoved);
            position[0] = q - s;
            position[1] = c - expectedRemoved;
        }
    }

    private static void assertParity(Run run, String when) {
        Map<String, Long> ledgerQuantity = new HashMap<>();
        RealPositions.of(run.fills).forEach(p -> ledgerQuantity.put(p.mint(), p.tokenBaseUnits()));
        Map<String, long[]> wallet = new HashMap<>();
        for (RealPosition position : run.accumulator.openPositions()) {
            long cost = ((RealCost.Known) position.cost()).lamports();
            wallet.put(position.mint(), new long[] {position.tokenBaseUnits(), cost});
        }
        Map<String, long[]> holders = new HashMap<>();
        for (Holder holder : HOLDERS) {
            for (RealPosition position : run.accumulator.openPositions(holder)) {
                long cost = ((RealCost.Known) position.cost()).lamports();
                long[] sum = holders.computeIfAbsent(position.mint(), m -> new long[2]);
                sum[0] += position.tokenBaseUnits();
                sum[1] += cost;
                long[] recomputed = run.recomputed.get(holderKey(holder, position.mint()));
                assertThat(new long[] {position.tokenBaseUnits(), cost})
                        .as("oracle 3, %s of %s %s", position.mint(), holder, when)
                        .containsExactly(recomputed);
            }
        }
        for (String mint : List.of("XYZ", "ABC")) {
            long raw = run.rawQuantity.getOrDefault(mint, 0L);
            long[] sum = holders.getOrDefault(mint, new long[2]);
            long[] read = wallet.getOrDefault(mint, new long[2]);
            assertThat(ledgerQuantity.getOrDefault(mint, 0L))
                    .as("oracle 1, path %s %s", mint, when)
                    .isEqualTo(raw);
            assertThat(sum[0]).as("oracle 1, holders %s %s", mint, when).isEqualTo(raw);
            assertThat(read[0]).as("item 4, quantity %s %s", mint, when).isEqualTo(raw);
            assertThat(read[1]).as("item 4, cost %s %s", mint, when).isEqualTo(sum[1]);
            if (!run.overSold) {
                long balance = run.boughtUnits.getOrDefault(mint, 0L) - run.soldUnits.getOrDefault(mint, 0L);
                assertThat(balance)
                        .as("oracle 1, token balance %s %s", mint, when)
                        .isEqualTo(raw);
            }
            assertThat(run.rawCost.getOrDefault(mint, 0L))
                    .as("oracle 2, cost conservation %s %s", mint, when)
                    .isEqualTo(run.removedByMint.getOrDefault(mint, 0L) + sum[1]);
        }
    }

    private static String holderKey(Holder holder, String mint) {
        return (holder.unowned()
                        ? "UNOWNED"
                        : holder.botId().map(UUID::toString).orElseThrow()) + "/" + mint;
    }
}
