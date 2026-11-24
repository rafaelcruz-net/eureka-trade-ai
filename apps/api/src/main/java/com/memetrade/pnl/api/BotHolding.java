package com.memetrade.pnl.api;

import java.util.Objects;

/**
 * 261121-paper-real-unico U4 (C15, ENT-402): the own position of a REAL bot in one mint, in one snapshot: its quantity
 * and cost by the attribution of the U2 (since the epoch of the mint), minus its open sales. No price, fee, period,
 * realized value nor line "Sem bot". Base units and lamports, exact.
 *
 * @param ownTokenBaseUnits the quantity of the holder bot in the ledger of the snapshot (BR1.1)
 * @param ownCostLamports the cost of the holder bot by the RealCostBasis (U2 BR2.1, BR2.2)
 * @param openSellBaseUnits the sum of the open sales of the bot in the same snapshot (ENT-405, BR1.3)
 * @param availableTokenBaseUnits {@code max(0, own - open)} (BR1.2)
 */
public record BotHolding(
        String mint,
        long ownTokenBaseUnits,
        long ownCostLamports,
        long openSellBaseUnits,
        long availableTokenBaseUnits) {

    public BotHolding {
        Objects.requireNonNull(mint, "mint");
        if (ownTokenBaseUnits < 0 || ownCostLamports < 0 || openSellBaseUnits < 0 || availableTokenBaseUnits < 0) {
            throw new IllegalArgumentException("a holding is never negative");
        }
        if (availableTokenBaseUnits > ownTokenBaseUnits) {
            throw new IllegalArgumentException("the available quantity never passes the own quantity");
        }
        if (ownTokenBaseUnits == 0 && ownCostLamports != 0) {
            throw new IllegalArgumentException("no own quantity has no own cost");
        }
        if (availableTokenBaseUnits != Math.max(0, ownTokenBaseUnits - openSellBaseUnits)) {
            throw new IllegalArgumentException("the available quantity is the own minus the open sales, at least 0");
        }
    }
}
