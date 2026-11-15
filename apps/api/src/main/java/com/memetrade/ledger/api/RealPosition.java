package com.memetrade.ledger.api;

import java.util.Objects;

/**
 * M2 {@code positions} (261113-trading-real-bot U3, entities.md RealPosition, Q11): an open position of a bot wallet,
 * derived from the swap fills of the REAL ledger. Quantity in base units of the token, cost in lamports.
 *
 * @param costLamports the average cost of the quantity still open: the SOL leg of the buys, reduced in proportion at
 *     each sell (rounded so the remaining cost is never understated)
 */
public record RealPosition(String mint, long tokenBaseUnits, long costLamports) {

    public RealPosition {
        Objects.requireNonNull(mint, "mint");
        if (tokenBaseUnits < 1 || costLamports < 0) {
            throw new IllegalArgumentException("an open position has at least 1 base unit and a cost of at least 0");
        }
    }
}
