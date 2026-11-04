package com.memetrade.pnl.domain;

import java.math.BigDecimal;

/** The exit valuation of one open position now (BR6.2), or why it is unknown (BR6.3, BR6.4). */
public sealed interface Mark permits Mark.Valued, Mark.Unvalued {

    /**
     * @param consumedPct consumption of the liquidity by the whole position, 4 decimals, at most 100
     * @param exitPrice {@code price(reference x (1 - (spread + consumed) / 100))}, never below zero
     * @param grossExitValue {@code money(quantity x exitPrice)}
     * @param estimatedFee {@code money(gross x fee / 100)}; never added to the fees paid
     * @param netExitValue gross minus the estimated fee
     * @param unrealized net exit value minus the cost basis
     */
    record Valued(
            BigDecimal consumedPct,
            BigDecimal exitPrice,
            BigDecimal grossExitValue,
            BigDecimal estimatedFee,
            BigDecimal netExitValue,
            BigDecimal unrealized)
            implements Mark {}

    record Unvalued(UnrealizedUnknownReason reason) implements Mark {}

    /** Why the unrealized result of a line is unknown (C5 {@code PnlAmount} UNKNOWN). */
    enum UnrealizedUnknownReason {
        PRICE_UNKNOWN,
        STALE,
        NO_LIQUIDITY
    }
}
