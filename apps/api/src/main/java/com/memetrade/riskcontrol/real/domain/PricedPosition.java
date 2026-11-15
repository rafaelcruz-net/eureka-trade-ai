package com.memetrade.riskcontrol.real.domain;

import java.util.Objects;

/**
 * 261113-trading-real-bot U3 (BR5.2, BR2.4): an open position of the wallet (M2) with its price in lamports per base
 * unit (M4), known or unknown.
 */
public record PricedPosition(String mint, long tokenBaseUnits, long costLamports, LamportPrice price) {

    public PricedPosition {
        Objects.requireNonNull(mint, "mint");
        Objects.requireNonNull(price, "price");
    }
}
