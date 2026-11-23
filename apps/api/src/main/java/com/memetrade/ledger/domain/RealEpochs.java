package com.memetrade.ledger.domain;

import com.memetrade.ledger.api.NetworkOrderKey;
import com.memetrade.ledger.api.NetworkOrderedEntry;
import com.memetrade.ledger.api.RealEntryKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 261121-paper-real-unico U2a, Etapa 14a (decision of the founder of 2026-11-22, NFR1.4): the EPOCH of each mint of a
 * wallet, pure. Fed with the swap fills and the RENT rows in network order, it keeps, per mint, only the rows after the
 * last time the quantity of the wallet in that mint (the quantity-only path: IN adds, OUT removes min(sold, current))
 * reached zero. At zero every holder of the mint is at zero, cost included (the quantity of the wallet is the sum of its
 * holders, BR9.1), so the cost read from the epoch on equals the cost read from the whole history. A RENT row belongs
 * to the epoch of the buy of its reference, only while that buy is in the open epoch of its mint. Nothing is stored: it
 * is computed at every read.
 *
 * <p>Etapa 14b: it is also the one source of the marks of zero ({@link RealEpochMarks}). Fed with the rows after a mark
 * (where the quantity was zero), it finds the same epochs as with the whole history; it keeps, per mint, the last zero
 * point it saw (the key of the row after which the quantity was zero and how many fills it read up to it) and the
 * earliest creation instant of the rows of the open epoch.
 */
public final class RealEpochs {

    /** The last zero of a mint in what was read: the row after which the quantity was 0, and the fills up to it. */
    public record ZeroPoint(NetworkOrderKey key, long fillsThrough) {}

    private final RealPositions.Quantities quantities = new RealPositions.Quantities();
    private final Map<String, List<NetworkOrderedEntry>> byMint = new LinkedHashMap<>();
    private final Map<String, String> mintOfReference = new HashMap<>();
    private final Map<String, Long> fills = new LinkedHashMap<>();
    private final Map<String, ZeroPoint> zeros = new HashMap<>();

    /** The open lots of {@code rows}, already in network order. */
    public static List<NetworkOrderedEntry> openLots(List<NetworkOrderedEntry> rows) {
        RealEpochs epochs = new RealEpochs();
        rows.forEach(epochs::accept);
        return epochs.openLots();
    }

    /** One swap fill or RENT row, in network order; any other kind is ignored. */
    public void accept(NetworkOrderedEntry entry) {
        if (entry.kind() == RealEntryKind.RENT) {
            String mint = mintOfReference.get(entry.reference());
            if (mint != null) {
                byMint.get(mint).add(entry);
            }
            return;
        }
        if (entry.kind() != RealEntryKind.SWAP_FILL_IN && entry.kind() != RealEntryKind.SWAP_FILL_OUT) {
            return;
        }
        String mint = entry.mint().orElseThrow();
        quantities.apply(new RealPositions.Fill(
                entry.kind(), entry.lamports(), mint, entry.tokenBaseUnits().orElseThrow()));
        long read = fills.merge(mint, 1L, Long::sum);
        byMint.computeIfAbsent(mint, m -> new ArrayList<>()).add(entry);
        mintOfReference.put(entry.reference(), mint);
        if (quantities.quantity(mint) == 0) {
            zeros.put(mint, new ZeroPoint(entry.key(), read));
            close(mint);
        }
    }

    /** The epoch of {@code mint} ends: its rows and the references of its buys leave the open lots. */
    private void close(String mint) {
        for (NetworkOrderedEntry row : byMint.remove(mint)) {
            mintOfReference.remove(row.reference(), mint);
        }
    }

    /** The quantity-only path fed so far (the positions of the view). */
    public RealPositions.Quantities quantities() {
        return quantities;
    }

    /** The rows of every open mint after its epoch, merged back in network order. */
    public List<NetworkOrderedEntry> openLots() {
        List<NetworkOrderedEntry> all = new ArrayList<>();
        byMint.values().forEach(all::addAll);
        all.sort(Comparator.comparing(NetworkOrderedEntry::key));
        return all;
    }

    /** Every mint with at least one swap fill read, in the order they first appeared. */
    public Set<String> mints() {
        return new LinkedHashSet<>(fills.keySet());
    }

    /** The swap fills of {@code mint} read. */
    public long fills(String mint) {
        return fills.getOrDefault(mint, 0L);
    }

    /** The last zero of {@code mint} in what was read; empty when its quantity never reached zero there. */
    public Optional<ZeroPoint> zeroPoint(String mint) {
        return Optional.ofNullable(zeros.get(mint));
    }

    /** The earliest creation instant of the rows of the open epoch of {@code mint}; empty when it is closed. */
    public Optional<Instant> openedAt(String mint) {
        return byMint.getOrDefault(mint, List.of()).stream()
                .map(NetworkOrderedEntry::createdAt)
                .min(Comparator.naturalOrder());
    }
}
