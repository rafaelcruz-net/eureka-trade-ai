package com.memetrade.ledger.api;

import java.util.List;
import java.util.Objects;

/**
 * M2 {@code view} (261113-trading-real-bot U3, review R-01): the balance, the open positions and the flows after an
 * instant of one bot wallet, read in ONE SQL statement, so they come from one snapshot of PostgreSQL. A fill or a
 * deposit committed while it is read is in all three parts or in none: it is never counted twice nor half.
 *
 * <p>261121-paper-real-unico U2 (BR2.5, F-3, performance-design.md "Consistência com o view"): the positions carry the
 * quantity of the quantity-only path in network order and a cost NOT read; {@code walletRowCount} is the number of
 * rows of the wallet, of every kind, with the same filter as the read in network order. The read of cost with
 * attribution, done after this view in the same transaction, compares it with the rows it streamed: the ledger is
 * append-only, so equal numbers mean the same set of rows.
 *
 * @param flows the deposits and withdrawals after the instant asked for; {@link RealFlows#NONE} when none was asked
 * @param walletRowCount every row of the wallet read by the same statement
 * @param openLots 261121-paper-real-unico U2a (Etapa 14a, the epoch): the swap fills and the RENT of the buys of
 *     each mint after the last time the quantity of the wallet in that mint reached zero, in network order, from the
 *     same statement; the read of cost with attribution starts there (before it every holder of the mint is at zero)
 */
public record RealLedgerView(
        RealBalance balance,
        List<RealPosition> positions,
        RealFlows flows,
        long walletRowCount,
        List<NetworkOrderedEntry> openLots) {

    /** A view without the open lots (tests of the readers of the quantity). */
    public RealLedgerView(RealBalance balance, List<RealPosition> positions, RealFlows flows, long walletRowCount) {
        this(balance, positions, flows, walletRowCount, List.of());
    }

    public RealLedgerView {
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(flows, "flows");
        positions = List.copyOf(positions);
        openLots = List.copyOf(openLots);
        if (walletRowCount < 0) {
            throw new IllegalArgumentException("a count of rows is never negative");
        }
    }
}
