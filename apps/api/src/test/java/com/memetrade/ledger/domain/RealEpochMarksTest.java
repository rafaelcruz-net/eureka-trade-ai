package com.memetrade.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.ledger.api.NetworkOrderKey;
import com.memetrade.ledger.api.NetworkOrderedEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.SlotStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U2a, Etapa 14b (written before the code; decision of the founder of 2026-11-22, NFR1.26):
 * the zero point of each mint found by {@link RealEpochs} and the marks {@link RealEpochMarks} plans from it. A mark is
 * derived from the quantity-only path (BR2.5), never a cost; a new one is planned for a new zero, for a mark a late
 * confirmation invalidated (OUT_OF_ORDER, or a full-read mark when no zero is left) and for a row written before the
 * window of a mark (EARLY_ROW). Nothing here updates or deletes a mark: the plan only adds.
 */
class RealEpochMarksTest {

    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");

    private final List<NetworkOrderedEntry> rows = new ArrayList<>();
    private long slot;

    // ----- RealEpochs: the zero point of each mint -------------------------------------------------------------------

    @Test
    void aSaleThatZeroesTheMintIsItsZeroPointWithEveryFillCounted() {
        buy("XYZ", 100, 0);
        buy("XYZ", 50, 0);
        NetworkOrderedEntry sale = sell("XYZ", 150);

        RealEpochs epochs = read(rows);

        assertThat(epochs.zeroPoint("XYZ")).contains(new RealEpochs.ZeroPoint(sale.key(), 3));
        assertThat(epochs.fills("XYZ")).isEqualTo(3);
        assertThat(epochs.openedAt("XYZ")).isEmpty();
    }

    @Test
    void aMintThatNeverReachedZeroHasNoZeroPointAndOpensAtItsFirstRow() {
        NetworkOrderedEntry first = buy("XYZ", 100, 2_039_280);
        sell("XYZ", 40);

        RealEpochs epochs = read(rows);

        assertThat(epochs.zeroPoint("XYZ")).isEmpty();
        assertThat(epochs.openedAt("XYZ")).contains(first.createdAt());
        assertThat(epochs.mints()).containsExactly("XYZ");
    }

    @Test
    void aSaleOnAZeroQuantityMovesTheZeroPointToIt() {
        buy("XYZ", 10, 0);
        sell("XYZ", 10);
        NetworkOrderedEntry overSale = sell("XYZ", 5);

        RealEpochs epochs = read(rows);

        assertThat(epochs.zeroPoint("XYZ")).contains(new RealEpochs.ZeroPoint(overSale.key(), 3));
        assertThat(epochs.quantities().quantity("XYZ")).isZero();
    }

    @Test
    void theRentOfABuyOfAClosedEpochNeverEntersTheNextEpoch() {
        NetworkOrderedEntry closedBuy = buy("XYZ", 10, 0);
        sell("XYZ", 10);
        buy("XYZ", 7, 0);
        // A RENT of the closed buy that lands after the new buy in network order (a stray row).
        rows.add(row(RealEntryKind.RENT, null, 0, -2_039_280, closedBuy.reference(), ++slot, 2));

        RealEpochs epochs = read(rows);

        assertThat(epochs.openLots()).extracting(NetworkOrderedEntry::kind).containsExactly(RealEntryKind.SWAP_FILL_IN);
    }

    // ----- RealEpochMarks: the plan
    // ------------------------------------------------------------------------------------

    @Test
    void aZeroFoundInTheRestrictedReadIsMarkedWithTheFillsOfTheStoredMarkAdded() {
        EpochMark stored = new EpochMark("XYZ", Optional.of(key(1)), 4, NOW.minusSeconds(600), EpochMarkReason.ZERO);
        buy("XYZ", 20, 0);
        NetworkOrderedEntry sale = sell("XYZ", 20);

        List<EpochMark> plan = RealEpochMarks.plan(Map.of("XYZ", stored), read(rows), Set.of(), Optional.empty(), NOW);

        assertThat(plan).containsExactly(new EpochMark("XYZ", Optional.of(sale.key()), 6, NOW, EpochMarkReason.ZERO));
    }

    @Test
    void theWindowOfANewMarkStartsAtTheEarliestRowOfTheOpenEpoch() {
        buy("XYZ", 20, 0);
        NetworkOrderedEntry sale = sell("XYZ", 20);
        NetworkOrderedEntry reopened = buy("XYZ", 5, 2_039_280);

        List<EpochMark> plan = RealEpochMarks.plan(Map.of(), read(rows), Set.of(), Optional.empty(), NOW);

        assertThat(plan)
                .containsExactly(
                        new EpochMark("XYZ", Optional.of(sale.key()), 2, reopened.createdAt(), EpochMarkReason.ZERO));
    }

    @Test
    void noNewZeroPlansNothing() {
        EpochMark stored = new EpochMark("XYZ", Optional.of(key(1)), 2, NOW.minusSeconds(60), EpochMarkReason.ZERO);
        buy("XYZ", 20, 0);
        buy("ABC", 3, 0);

        assertThat(RealEpochMarks.plan(Map.of("XYZ", stored), read(rows), Set.of(), Optional.empty(), NOW))
                .isEmpty();
    }

    @Test
    void aMarkALateConfirmationInvalidatedIsReplacedByTheZeroOfTheFullRead() {
        EpochMark stale = new EpochMark("XYZ", Optional.of(key(9)), 2, NOW.minusSeconds(60), EpochMarkReason.ZERO);
        buy("XYZ", 20, 0);
        NetworkOrderedEntry sale = sell("XYZ", 20);
        buy("XYZ", 10, 0);

        List<EpochMark> plan =
                RealEpochMarks.plan(Map.of("XYZ", stale), read(rows), Set.of("XYZ"), Optional.empty(), NOW);

        assertThat(plan)
                .containsExactly(new EpochMark(
                        "XYZ", Optional.of(sale.key()), 2, rows.get(2).createdAt(), EpochMarkReason.OUT_OF_ORDER));
    }

    @Test
    void whenTheFullReadFindsNoZeroAnyMoreTheMintFallsBackToAFullReadMark() {
        EpochMark stale = new EpochMark("XYZ", Optional.of(key(9)), 2, NOW.minusSeconds(60), EpochMarkReason.ZERO);
        NetworkOrderedEntry first = buy("XYZ", 20, 0);
        sell("XYZ", 5);

        List<EpochMark> plan =
                RealEpochMarks.plan(Map.of("XYZ", stale), read(rows), Set.of("XYZ"), Optional.empty(), NOW);

        assertThat(plan)
                .containsExactly(
                        new EpochMark("XYZ", Optional.empty(), 0, first.createdAt(), EpochMarkReason.OUT_OF_ORDER));
    }

    @Test
    void aFullReadKeepsAValidMarkThatIsAlreadyTheZero() {
        buy("XYZ", 20, 0);
        NetworkOrderedEntry sale = sell("XYZ", 20);
        EpochMark same = new EpochMark("XYZ", Optional.of(sale.key()), 2, NOW.minusSeconds(60), EpochMarkReason.ZERO);
        buy("ABC", 1, 0);

        assertThat(RealEpochMarks.plan(Map.of("XYZ", same), read(rows), Set.of("ABC"), Optional.empty(), NOW))
                .isEmpty();
    }

    @Test
    void aRowWrittenBeforeTheWindowOfAMarkLowersItsWindowAndTouchesNoOtherMark() {
        Instant early = NOW.minusSeconds(3_600);
        EpochMark late = new EpochMark("XYZ", Optional.of(key(1)), 2, NOW.minusSeconds(60), EpochMarkReason.ZERO);
        EpochMark older = new EpochMark("ABC", Optional.of(key(2)), 2, early.minusSeconds(1), EpochMarkReason.ZERO);

        List<EpochMark> plan =
                RealEpochMarks.plan(Map.of("XYZ", late, "ABC", older), read(rows), Set.of(), Optional.of(early), NOW);

        assertThat(plan).containsExactly(new EpochMark("XYZ", late.zero(), 2, early, EpochMarkReason.EARLY_ROW));
    }

    @Test
    void aMarkNeverCarriesFillsWithoutAZeroNorANegativeCount() {
        assertThatThrownBy(() -> new EpochMark("XYZ", Optional.empty(), 1, NOW, EpochMarkReason.OUT_OF_ORDER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EpochMark("XYZ", Optional.of(key(1)), -1, NOW, EpochMarkReason.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EpochMark(" ", Optional.of(key(1)), 0, NOW, EpochMarkReason.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ----- helpers ---------------------------------------------------------------------------------------------------

    private static RealEpochs read(List<NetworkOrderedEntry> rows) {
        RealEpochs epochs = new RealEpochs();
        rows.forEach(epochs::accept);
        return epochs;
    }

    private NetworkOrderedEntry buy(String mint, long units, long rent) {
        String reference = "order:" + UUID.randomUUID();
        NetworkOrderedEntry fill = row(RealEntryKind.SWAP_FILL_IN, mint, units, -1_000_000, reference, ++slot, 0);
        rows.add(fill);
        if (rent > 0) {
            rows.add(row(RealEntryKind.RENT, null, 0, -rent, reference, slot, 2));
        }
        return fill;
    }

    private NetworkOrderedEntry sell(String mint, long units) {
        NetworkOrderedEntry fill =
                row(RealEntryKind.SWAP_FILL_OUT, mint, units, 900_000, "order:" + UUID.randomUUID(), ++slot, 0);
        rows.add(fill);
        return fill;
    }

    private static NetworkOrderKey key(long slot) {
        return new NetworkOrderKey(
                slot, false, Optional.of("Sig" + slot), Instant.EPOCH.plusSeconds(slot), "r" + slot, 0, "k" + slot);
    }

    private static NetworkOrderedEntry row(
            RealEntryKind kind, String mint, long units, long lamports, String reference, long slot, int rank) {
        Optional<String> signature = Optional.of("Sig" + reference);
        NetworkOrderKey key = new NetworkOrderKey(
                slot, false, signature, NOW.minusSeconds(10_000 - slot), reference, rank, reference + ":" + kind);
        return new NetworkOrderedEntry(
                UUID.randomUUID(),
                UUID.randomUUID(),
                kind,
                lamports,
                Optional.ofNullable(mint),
                kind == RealEntryKind.RENT ? Optional.empty() : Optional.of(units),
                signature,
                reference,
                key.createdAt(),
                key,
                SlotStatus.RECORDED);
    }
}
