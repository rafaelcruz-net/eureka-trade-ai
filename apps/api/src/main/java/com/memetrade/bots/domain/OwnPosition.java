package com.memetrade.bots.domain;

import java.util.Objects;

/**
 * 261121-paper-real-unico U4 (ENT-402 as the domain of the bots sees it): the own position of a REAL bot in one mint,
 * in base units and lamports: its own quantity and cost, and what of it is available (its own minus its open sales).
 */
public record OwnPosition(String mint, long ownTokenBaseUnits, long ownCostLamports, long availableTokenBaseUnits) {

    public OwnPosition {
        Objects.requireNonNull(mint, "mint");
        if (ownTokenBaseUnits < 0 || ownCostLamports < 0 || availableTokenBaseUnits < 0) {
            throw new IllegalArgumentException("an own position is never negative");
        }
        if (availableTokenBaseUnits > ownTokenBaseUnits) {
            throw new IllegalArgumentException("the available quantity never passes the own quantity");
        }
        if (ownTokenBaseUnits == 0 && ownCostLamports != 0) {
            throw new IllegalArgumentException("no own quantity has no own cost");
        }
    }
}
