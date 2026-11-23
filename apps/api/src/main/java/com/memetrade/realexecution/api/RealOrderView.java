package com.memetrade.realexecution.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * M6 reads (contract R3 {@code RealOrder}): one REAL order as its owner and the other modules see it. Never an address
 * nor a transaction; the on-chain signature only when it exists. Lamports and base units are {@code long}.
 *
 * @param operationMode the mode of the wallet when the order was created ({@code SIGNED}, {@code AUTO_EXITS} or
 *     {@code AUTO_FULL})
 * @param feePayer {@code BOT_WALLET} or {@code MAIN_WALLET}, when the fee is known
 */
public record RealOrderView(
        UUID realOrderId,
        Optional<UUID> botId,
        Optional<UUID> liquidationId,
        String origin,
        RealSide side,
        String mint,
        Optional<Long> inputLamports,
        Optional<Long> inputBaseUnits,
        Optional<Long> expectedOutBaseUnits,
        Optional<Long> minOutBaseUnits,
        Optional<Long> outBaseUnits,
        String operationMode,
        RealOrderState state,
        Optional<RealFailureCode> failureCode,
        Optional<String> txSignature,
        Optional<Long> feeLamports,
        Optional<String> feePayer,
        Optional<Instant> signingDeadline,
        Instant createdAt,
        Instant updatedAt,
        // Review of the U4 (Minor 6): the rent the bot wallet paid for an account the buy created, read from the
        // network.
        Optional<Long> rentLamports,
        // 261121-paper-real-unico U2 (review R-04): the bot wallet of the order (an id, never an address); empty on
        // rows written before the review of the U4.
        Optional<UUID> botWalletId) {

    public RealOrderView {
        Objects.requireNonNull(realOrderId, "realOrderId");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(mint, "mint");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(rentLamports, "rentLamports");
        Objects.requireNonNull(botWalletId, "botWalletId");
    }
}
