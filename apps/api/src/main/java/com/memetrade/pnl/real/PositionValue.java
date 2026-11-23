package com.memetrade.pnl.real;

import java.util.Objects;
import java.util.Optional;

/**
 * 261121-paper-real-unico U2 (BR10.3, BR2.4, BR7.2, BR7.3): an open REAL position of the person in one mint, the sum of
 * the bot wallets: the quantity of the quantity-only path, the cost as the sum of the holders, and, when the price is
 * known, its value {@code floor(Q x p)} per wallet and its unrealized. An unknown price leaves both empty, never 0. The
 * positions of {@code GET /api/v1/real/pnl}.
 */
public record PositionValue(
        String mint,
        long tokenBaseUnits,
        long costLamports,
        Optional<Long> valueLamports,
        Optional<Long> unrealizedLamports) {

    public PositionValue {
        Objects.requireNonNull(mint, "mint");
        Objects.requireNonNull(valueLamports, "valueLamports");
        Objects.requireNonNull(unrealizedLamports, "unrealizedLamports");
        if (tokenBaseUnits < 1 || costLamports < 0) {
            throw new IllegalArgumentException("an open position has at least 1 base unit and a cost of at least 0");
        }
    }
}
