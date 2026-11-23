package com.memetrade.pnl.real;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.ledger.api.NetworkOrderKey;
import com.memetrade.ledger.api.NetworkOrderedEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.SlotStatus;
import com.memetrade.pnl.api.BotPnlStatus;
import com.memetrade.pnl.api.LamportsValue;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.RealBotPnlLine;
import com.memetrade.pnl.api.RealModePnl;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U2, Etapa 18 (WRITTEN BEFORE THE CODE; BR9.2, BR9.1, BR8.1 to BR8.5, AC3.6.3, NFR2.7): random
 * REAL ledgers of one bot wallet, in the pattern of {@code LedgerPropertyTest}: three FIXED seeds, 350 sequences each
 * (1.050 per run) of 10 to 60 events: buys of bots and without a bot, own sales with and without excess, liquidations,
 * whole and partial conditionals, failed orders (fee paid by the bot wallet or by the main wallet), fees of the mode
 * Assinada, events in the same slot, rows without a recorded slot (SLOT_MISSING), sales beyond what is recorded and a
 * removed bot. For each sequence:
 *
 * <ul>
 *   <li>the reconciliation is MATCH since the beginning and in a random period;
 *   <li>the quantity of each mint equals the raw oracle over the swap fills (BR9.1 item 1);
 *   <li>the fees and the proceeds equal the raw oracle over the rows of the ledger and the orders (review R-03,
 *       {@link #rawFeesAndProceeds});
 *   <li>two runs give the same result; the rows with a recorded slot recorded at other instants give the same result;
 *   <li>nothing is negative and a zeroed position has no cost.
 * </ul>
 *
 * The seed and the index of the sequence are in every failure message.
 */
class RealPnlPropertyTest {

    private static final long[] SEEDS = {20261123L, 7_777_777L, 424_242L};
    private static final int SEQUENCES_PER_SEED = 350;
    private static final Holder A = RealCostBasisTest.A;
    private static final Holder B = RealCostBasisTest.B;
    private static final Holder C = RealCostBasisTest.C;
    private static final List<Holder> BOTS = List.of(A, B, C);
    private static final List<String> MINTS = List.of("XYZ", "ABC");
    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    void everyRandomLedgerReconcilesExactlyAndDeterministically() {
        int checked = 0;
        for (long seed : SEEDS) {
            Random random = new Random(seed);
            for (int index = 0; index < SEQUENCES_PER_SEED; index++) {
                check(random, "seed " + seed + ", sequence " + index);
                checked++;
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(1_000);
    }

    private static void check(Random random, String where) {
        PnlScenario ledger = new PnlScenario()
                .bot(A)
                .bot(B, random.nextBoolean() ? BotPnlStatus.REMOVED : BotPnlStatus.PAUSED)
                .bot(C);
        int events = 10 + random.nextInt(51);
        for (int i = 0; i < events; i++) {
            event(ledger, random);
        }
        Map<String, Optional<BigDecimal>> prices = new HashMap<>();
        for (String mint : MINTS) {
            prices.put(
                    mint,
                    random.nextInt(4) == 0
                            ? Optional.empty()
                            : Optional.of(BigDecimal.valueOf(random.nextInt(5_000_000))
                                    .divide(
                                            BigDecimal.valueOf(1 + random.nextInt(7)),
                                            18,
                                            java.math.RoundingMode.DOWN)));
        }

        RealPnlResult all = ledger.run(PnlWindow.all(), prices);
        RealPnlResult again = ledger.run(PnlWindow.all(), prices);
        Instant from = START.plus(Duration.ofMinutes(random.nextInt(events + 2)));
        Instant to = from.plus(Duration.ofMinutes(1 + random.nextInt(events + 2)));
        RealPnlResult period = ledger.run(new PnlWindow.Range(from, to), prices);

        assertThat(all.reconciliation().status())
                .as("%s: %s", where, all.reconciliation())
                .isEqualTo(ReconciliationResult.Status.MATCH);
        assertThat(period.reconciliation().status())
                .as("%s [%s, %s): %s", where, from, to, period.reconciliation())
                .isEqualTo(ReconciliationResult.Status.MATCH);
        assertThat(again).as(where).isEqualTo(all);
        if (ledger.virtualAttempts().isEmpty()) {
            // A virtual attempt of the mode Assinada is placed by its instant among the rows (BR6.2), so the check of
            // the recorded rows moved to other instants only holds without one.
            assertThat(sums(ledger.run(PnlWindow.all(), prices, recordedAtOtherInstants(ledger, random))))
                    .as("%s: the rows with a recorded slot recorded at other instants", where)
                    .isEqualTo(sums(all.pnl()));
        }
        assertQuantitiesEqualTheRawOracle(ledger, all, where);
        assertThat(rawFeesAndProceeds(ledger, all))
                .as("%s: fees and proceeds against the raw rows of the ledger", where)
                .isEmpty();
        for (RealBotPnlLine line : all.pnl().lines()) {
            assertThat(line.feesLamports()).as(where).isNotNegative();
        }
        assertThat(all.pnl().total().feesLamports()).as(where).isNotNegative();
        for (PositionValue position : all.positions()) {
            assertThat(position.tokenBaseUnits()).as(where).isPositive();
            assertThat(position.costLamports()).as(where).isNotNegative();
        }
    }

    private static void event(PnlScenario ledger, Random random) {
        ledger.mint(MINTS.get(random.nextInt(MINTS.size())));
        if (random.nextInt(12) == 0) {
            ledger.sameSlot();
        }
        if (random.nextInt(40) == 0) {
            ledger.slotMissing();
        }
        Holder bot = BOTS.get(random.nextInt(BOTS.size()));
        long units = 1 + random.nextInt(400);
        long lamports = random.nextInt(1_000_000_000);
        long fee = random.nextInt(3) == 0 ? 0 : 1 + random.nextInt(20_000);
        switch (random.nextInt(13)) {
            case 0, 1, 2 -> ledger.buy(bot, units, lamports, fee);
            case 3 -> ledger.buyWithRent(bot, units, lamports, 1 + random.nextInt(3_000_000), fee);
            case 4 -> ledger.signedBuy(bot, units, lamports, fee);
            case 5 -> ledger.purgedBuy(units, lamports);
            case 6, 7 -> ledger.ownSale(bot, units, lamports, fee);
            case 8 -> ledger.signedOwnSale(bot, units, lamports, fee);
            case 9 -> ledger.liquidation(units, lamports, fee);
            case 10 -> ledger.conditional(units, lamports, fee);
            case 11 -> {
                switch (random.nextInt(4)) {
                    case 0 -> ledger.failed(bot, 1 + fee);
                    case 1 -> ledger.signedFailed(bot, 1 + fee);
                    case 2 -> ledger.failedLiquidation(1 + fee);
                    default -> ledger.signedFailedLiquidation(1 + fee);
                }
            }
            default -> {
                if (random.nextBoolean()) {
                    ledger.purgedSale(units, lamports, fee);
                } else {
                    ledger.ownSaleWithRent(bot, units, lamports, fee, 1 + random.nextInt(2_000_000));
                }
            }
        }
    }

    /** BR9.1 item 1: per mint, IN adds and OUT removes min(sold, current), in network order. */
    private static void assertQuantitiesEqualTheRawOracle(PnlScenario ledger, RealPnlResult all, String where) {
        Map<String, Long> raw = new HashMap<>();
        for (NetworkOrderedEntry entry : ledger.entriesInNetworkOrder()) {
            if (entry.kind() == RealEntryKind.SWAP_FILL_IN) {
                raw.merge(entry.mint().orElseThrow(), entry.tokenBaseUnits().orElseThrow(), Math::addExact);
            } else if (entry.kind() == RealEntryKind.SWAP_FILL_OUT) {
                String mint = entry.mint().orElseThrow();
                long current = raw.getOrDefault(mint, 0L);
                raw.put(mint, current - Math.min(current, entry.tokenBaseUnits().orElseThrow()));
            }
        }
        raw.values().removeIf(units -> units == 0);
        Map<String, Long> positions = new HashMap<>();
        all.positions().forEach(p -> positions.put(p.mint(), p.tokenBaseUnits()));
        assertThat(positions).as("%s: quantity against the raw oracle", where).isEqualTo(raw);
    }

    /**
     * Review R-03: the raw oracle of the money, read from the rows of the ledger and the orders, never from the events
     * the engine assembled (so a trade row the engine drops or reads twice is caught, which the reconciliation, summed
     * over the same events, cannot see):
     *
     * <ul>
     *   <li>fees of the mode = every NETWORK_FEE row + every RENT row outside a buy + the fee of every order of the mode
     *       Assinada (paid by the main wallet, without a row; BR6.1 to BR6.6), and the lines plus "Sem bot" add up to it;
     *   <li>proceeds: the lamports received by every SWAP_FILL_OUT = the realized of the mode + the cost that left the
     *       positions, where that cost is what every buy paid (fill and RENT, BR2.1) minus the cost still open.
     * </ul>
     *
     * @return the differences found, empty when both hold
     */
    static List<String> rawFeesAndProceeds(PnlScenario ledger, RealPnlResult result) {
        Map<String, Boolean> eventWithBuy = new HashMap<>();
        for (NetworkOrderedEntry entry : ledger.entriesInNetworkOrder()) {
            if (entry.kind() == RealEntryKind.SWAP_FILL_IN) {
                eventWithBuy.put(event(entry), true);
            }
        }
        long fees = 0;
        long received = 0;
        long costIn = 0;
        for (NetworkOrderedEntry entry : ledger.entriesInNetworkOrder()) {
            boolean ofABuy = eventWithBuy.getOrDefault(event(entry), false);
            switch (entry.kind()) {
                case NETWORK_FEE -> fees = Math.addExact(fees, -entry.lamports());
                case RENT -> {
                    if (ofABuy) {
                        costIn = Math.addExact(costIn, -entry.lamports());
                    } else {
                        fees = Math.addExact(fees, -entry.lamports());
                    }
                }
                case SWAP_FILL_IN -> costIn = Math.addExact(costIn, -entry.lamports());
                case SWAP_FILL_OUT -> received = Math.addExact(received, entry.lamports());
                default -> {
                    // not a trading row (BR6.5)
                }
            }
        }
        for (OrderAttribution order : ledger.orders().values()) {
            if (order.feePaidByMainWallet()) {
                fees = Math.addExact(fees, order.feeLamports());
            }
        }
        long open = result.positions().stream()
                .mapToLong(PositionValue::costLamports)
                .reduce(0L, Math::addExact);
        List<String> differences = new ArrayList<>();
        RealModePnl pnl = result.pnl();
        long lines =
                pnl.lines().stream().mapToLong(RealBotPnlLine::feesLamports).reduce(0L, Math::addExact)
                        + pnl.unowned().map(u -> u.feesLamports()).orElse(0L);
        if (pnl.total().feesLamports() != fees) {
            differences.add("fees of the mode " + pnl.total().feesLamports() + " != raw " + fees);
        }
        if (lines != fees) {
            differences.add("fees of the lines " + lines + " != raw " + fees);
        }
        long realized = pnl.total().realizedLamports();
        if (Math.addExact(realized, Math.subtractExact(costIn, open)) != received) {
            differences.add("realized " + realized + " + cost removed " + (costIn - open) + " != received " + received);
        }
        return differences;
    }

    private static String event(NetworkOrderedEntry entry) {
        return entry.botWalletId() + "|" + entry.reference() + "|"
                + entry.txSignature().orElse("");
    }

    /**
     * Review R-03: the raw oracle catches what the reconciliation cannot: the stream loses one SWAP_FILL_OUT row (a
     * defect of a reader on purpose); the engine reconciles with itself, the raw rows do not agree.
     */
    @Test
    void theRawOracleCatchesADroppedTradeRow() {
        PnlScenario ledger = new PnlScenario().bot(A).bot(B);
        ledger.buy(A, 100, 100_000_000L, 5_000L);
        ledger.buy(B, 100, 100_000_000L, 5_000L);
        ledger.ownSale(A, 50, 70_000_000L, 5_000L);
        ledger.ownSale(B, 50, 40_000_000L, 5_000L);
        List<NetworkOrderedEntry> lossy = new ArrayList<>(ledger.entriesInNetworkOrder());
        lossy.remove(lossy.stream()
                .filter(entry -> entry.kind() == RealEntryKind.SWAP_FILL_OUT)
                .reduce((first, second) -> second)
                .orElseThrow());

        RealPnlResult complete = ledger.run(PnlWindow.all(), Map.of());
        RealPnlResult dropped = ledger.run(PnlWindow.all(), Map.of(), lossy);

        assertThat(rawFeesAndProceeds(ledger, complete)).isEmpty();
        assertThat(dropped.reconciliation().status())
                .as("summed over the same events, the reconciliation does not see the lost row")
                .isEqualTo(ReconciliationResult.Status.MATCH);
        assertThat(rawFeesAndProceeds(ledger, dropped)).isNotEmpty();
    }

    /** The same rows with a recorded slot moved to other instants (same slot, same signature): same network order. */
    private static List<NetworkOrderedEntry> recordedAtOtherInstants(PnlScenario ledger, Random random) {
        List<NetworkOrderedEntry> moved = new ArrayList<>();
        Map<String, Instant> bySignature = new HashMap<>();
        for (NetworkOrderedEntry entry : ledger.entriesInNetworkOrder()) {
            if (entry.slotStatus() != SlotStatus.RECORDED) {
                moved.add(entry);
                continue;
            }
            Instant at = bySignature.computeIfAbsent(
                    entry.txSignature().orElseThrow(),
                    signature -> START.plus(Duration.ofSeconds(random.nextInt(1_000_000))));
            NetworkOrderKey key = entry.key();
            moved.add(new NetworkOrderedEntry(
                    entry.entryId(),
                    entry.botWalletId(),
                    entry.kind(),
                    entry.lamports(),
                    entry.mint(),
                    entry.tokenBaseUnits(),
                    entry.txSignature(),
                    entry.reference(),
                    at,
                    new NetworkOrderKey(
                            key.effectiveSlot(),
                            key.inherited(),
                            key.txSignature(),
                            at,
                            key.reference(),
                            key.kindRank(),
                            key.idempotencyKey()),
                    entry.slotStatus()));
        }
        Collections.shuffle(moved, random);
        moved.sort(Comparator.comparing(NetworkOrderedEntry::key));
        return moved;
    }

    /** Every number of the block but the instants of the shares. */
    private static List<Object> sums(RealPnlResult result) {
        return sums(result.pnl());
    }

    private static List<Object> sums(RealModePnl pnl) {
        List<Object> values = new ArrayList<>();
        for (RealBotPnlLine line : pnl.lines()) {
            values.add(List.of(
                    line.botId(),
                    line.realizedLamports(),
                    line.feesLamports(),
                    text(line.unrealized()),
                    text(line.total()),
                    line.shares().size()));
        }
        values.add(pnl.unowned()
                .map(u -> List.of(u.realizedLamports(), u.feesLamports(), text(u.unrealized())))
                .orElse(List.of()));
        values.add(List.of(
                pnl.total().realizedLamports(),
                pnl.total().feesLamports(),
                text(pnl.total().unrealized()),
                text(pnl.total().total())));
        return values;
    }

    private static String text(LamportsValue value) {
        return switch (value) {
            case LamportsValue.Known known -> Long.toString(known.lamports());
            case LamportsValue.Unknown unknown -> unknown.reason().name();
        };
    }
}
