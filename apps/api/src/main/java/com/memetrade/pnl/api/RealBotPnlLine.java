package com.memetrade.pnl.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 261121-paper-real-unico U1, C10 (ENT-012, BR5.5): the P&L REAL of one bot, in lamports. In the U1 it is the average
 * cost of today applied only to the settled REAL orders of this bot; {@code realizedLamports} is NET of fees (the fee
 * of a buy is in the cost, those of a sell and of a failed order leave the realized), so
 * {@code total = realized + unrealized} and {@code feesLamports} is information only. From the U2 on,
 * {@code realizedLamports} becomes GROSS of fees and {@code total = realized + unrealized - fees} (contract C10).
 *
 * @param shares the prorated sales of the bot; always empty in the U1 (the U2 fills them)
 */
public record RealBotPnlLine(
        UUID botId,
        BotPnlStatus status,
        long realizedLamports,
        LamportsValue unrealized,
        long feesLamports,
        LamportsValue total,
        List<ProratedShare> shares) {

    public RealBotPnlLine {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(unrealized, "unrealized");
        Objects.requireNonNull(total, "total");
        shares = List.copyOf(shares);
    }
}
