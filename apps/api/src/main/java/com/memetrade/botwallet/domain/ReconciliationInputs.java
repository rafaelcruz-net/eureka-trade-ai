package com.memetrade.botwallet.domain;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 261113-trading-real-bot U5 (BR2.1): what one reconciliation reads. {@code chain} is empty when the network was not read
 * (something in transit) or did not answer.
 *
 * <p>261113-trading-real-bot U7 (BR5.3b, U7-NFR4.8): {@code escrows} has the base units of every mint held in the
 * custody accounts of the conditionals OPEN or CANCELLING of the wallet, read on the network; empty when a custody
 * account could not be read (the reconciliation is then UNAVAILABLE, never DIVERGENT).
 *
 * @param ordersInTransit REAL orders of the person SENT or AMBIGUOUS (M6)
 * @param withdrawalsInTransit withdrawals of the wallet SENT or AMBIGUOUS
 */
public record ReconciliationInputs(
        int ordersInTransit,
        int withdrawalsInTransit,
        Optional<ChainReading> chain,
        LedgerSnapshot ledger,
        Optional<Map<String, Long>> escrows) {

    public ReconciliationInputs {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(escrows, "escrows");
        escrows = escrows.map(Map::copyOf);
    }

    /** Without any custody (no conditional of the Jupiter open): the reading of the U5. */
    public ReconciliationInputs(
            int ordersInTransit, int withdrawalsInTransit, Optional<ChainReading> chain, LedgerSnapshot ledger) {
        this(ordersInTransit, withdrawalsInTransit, chain, ledger, Optional.of(Map.of()));
    }

    public int inTransit() {
        return Math.addExact(ordersInTransit, withdrawalsInTransit);
    }
}
