package com.memetrade.botwallet.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 261113-trading-real-bot U5 (BR2.1): what one reconciliation reads. {@code chain} is empty when the network was not read
 * (something in transit) or did not answer.
 *
 * @param ordersInTransit REAL orders of the person SENT or AMBIGUOUS (M6)
 * @param withdrawalsInTransit withdrawals of the wallet SENT or AMBIGUOUS
 */
public record ReconciliationInputs(
        int ordersInTransit, int withdrawalsInTransit, Optional<ChainReading> chain, LedgerSnapshot ledger) {

    public ReconciliationInputs {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(ledger, "ledger");
    }

    public int inTransit() {
        return Math.addExact(ordersInTransit, withdrawalsInTransit);
    }
}
