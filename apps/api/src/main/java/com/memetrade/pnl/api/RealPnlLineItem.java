package com.memetrade.pnl.api;

import java.time.Instant;
import java.util.Objects;

/**
 * 261121-paper-real-unico U3, C10 {@code botLines} (BR4.1, BR4.4, BR4.6; C5): one line of the P&L of a REAL bot, as the
 * pass of the {@code RealBotPnl} applied it to the bot: an own buy or sale, an event of a fee alone, or a prorated part
 * of a liquidation, a conditional or a sale of another bot. Exact lamports and base units; a buy realizes 0. On the
 * closed REAL list of NEVER_SUM_MODES.
 *
 * @param identity the idempotency key of the first ledger row of the event (or {@code attempt:<order>} for a failed
 *     attempt without a row); with {@code origin}, the identity of the line, the anchor of the cursor of C5. Never part
 *     of an answer.
 * @param amountLamports paid by a buy, received by a sale or a part; 0 for a fee alone
 */
public record RealPnlLineItem(
        Instant at,
        Side side,
        String mint,
        RealPnlLineOrigin origin,
        long tokenBaseUnits,
        long amountLamports,
        long feeLamports,
        long realizedLamports,
        String identity) {

    /** The side of the line: the buy spends lamports for the token, the sale gives the token for lamports. */
    public enum Side {
        BUY,
        SELL
    }

    public RealPnlLineItem {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(mint, "mint");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(identity, "identity");
        if (tokenBaseUnits < 0 || amountLamports < 0 || feeLamports < 0) {
            throw new IllegalArgumentException("a line never has a negative quantity, amount nor fee");
        }
        if (side == Side.BUY && realizedLamports != 0) {
            throw new IllegalArgumentException("a buy never realizes a result");
        }
    }
}
