package com.memetrade.pnl.real;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 261121-paper-real-unico U2 (ENT-204, C13, BR3.1, BR3.2, BR6.2, BR6.3): what the P&L needs of a REAL order that reached
 * the network: its bot (the only link between a REAL operation and a bot), whether it is a sale of the "vender tudo e
 * sacar", whether it failed, its mint, its fee and who paid it, and when it settled. Never an address.
 *
 * @param botId the bot of an order of origin BOT; empty for a liquidation
 * @param liquidation true for an order of origin USER_LIQUIDATION
 * @param failed true for an order FAILED on chain (only its fee counts)
 * @param feeLamports the network fee of the order, 0 when unknown
 * @param feePaidByMainWallet true in the mode Assinada: the fee is not in the ledger of the bot wallet (BR6.3)
 * @param settledAt when the order reached CONFIRMED or FAILED (the instant of a failed attempt without a row)
 * @param botWalletId the bot wallet of the order (review R-04: a failed attempt of the mode Assinada, without a row, is
 *     shared in its own wallet); empty only for an order read without it
 */
public record OrderAttribution(
        UUID realOrderId,
        Optional<UUID> botId,
        boolean liquidation,
        boolean failed,
        String mint,
        long feeLamports,
        boolean feePaidByMainWallet,
        Instant settledAt,
        Optional<UUID> botWalletId) {

    public OrderAttribution {
        Objects.requireNonNull(realOrderId, "realOrderId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(mint, "mint");
        Objects.requireNonNull(settledAt, "settledAt");
        Objects.requireNonNull(botWalletId, "botWalletId");
        if (feeLamports < 0) {
            throw new IllegalArgumentException("a fee is never negative");
        }
    }

    /** An order whose bot wallet is not known (the tests of one wallet). */
    public OrderAttribution(
            UUID realOrderId,
            Optional<UUID> botId,
            boolean liquidation,
            boolean failed,
            String mint,
            long feeLamports,
            boolean feePaidByMainWallet,
            Instant settledAt) {
        this(
                realOrderId,
                botId,
                liquidation,
                failed,
                mint,
                feeLamports,
                feePaidByMainWallet,
                settledAt,
                Optional.empty());
    }

    /** A failed attempt of the mode Assinada: no row in the ledger, only its fee on the order (BR6.2, BR6.3). */
    public boolean virtualAttempt() {
        return failed && feePaidByMainWallet && feeLamports > 0;
    }
}
