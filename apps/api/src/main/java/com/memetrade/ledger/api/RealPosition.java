package com.memetrade.ledger.api;

import java.util.Objects;

/**
 * M2 {@code positions} (261113-trading-real-bot U3, entities.md RealPosition, Q11): an open position of a bot wallet.
 * Quantity in base units of the token.
 *
 * <p>261121-paper-real-unico U2 (decision F-3, BR2.5, BR2.8): the quantity comes from the quantity-only path of the
 * ledger, in network order and without attribution; the cost is {@link RealCost}. {@code positions} and {@code view} of
 * the ledger never read it ({@code Unknown(NOT_READ)}); the cost of the wallet, the sum of its holders, comes from the
 * read with attribution ({@code pnl.api.RealWalletCost}) and only for the readers that need it.
 */
public record RealPosition(String mint, long tokenBaseUnits, RealCost cost) {

    public RealPosition {
        Objects.requireNonNull(mint, "mint");
        Objects.requireNonNull(cost, "cost");
        if (tokenBaseUnits < 1) {
            throw new IllegalArgumentException("an open position has at least 1 base unit");
        }
    }

    /** The same position with {@code cost} (the read with attribution replacing NOT_READ). */
    public RealPosition withCost(RealCost cost) {
        return new RealPosition(mint, tokenBaseUnits, cost);
    }
}
