package com.memetrade.riskcontrol.real.domain;

import com.memetrade.ledger.api.RealCost;
import java.util.Objects;

/**
 * 261113-trading-real-bot U3 (BR5.2, BR2.4): an open position of the wallet (M2) with its price in lamports per base
 * unit (M4), known or unknown. 261121-paper-real-unico U2 (F-3): the cost is {@link RealCost}: NOT read for a buy and
 * for a priced position, read with attribution only by a round that needs it.
 */
public record PricedPosition(String mint, long tokenBaseUnits, RealCost cost, LamportPrice price) {

    public PricedPosition {
        Objects.requireNonNull(mint, "mint");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(price, "price");
    }

    /** A position with a known cost. */
    public PricedPosition(String mint, long tokenBaseUnits, long costLamports, LamportPrice price) {
        this(mint, tokenBaseUnits, new RealCost.Known(costLamports), price);
    }
}
