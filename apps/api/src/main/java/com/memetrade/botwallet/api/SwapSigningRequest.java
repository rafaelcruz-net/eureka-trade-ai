package com.memetrade.botwallet.api;

import java.util.Objects;
import java.util.UUID;

/**
 * M1 {@code signSwap} (entities.md SwapSigningRequest, contract R1 addendum of the U4): one swap to sign. The context
 * can only restrict: the TxSigner checks the transaction against it and against its own policy.
 *
 * @param mode {@code AUTO_FULL} (the bot wallet signs alone) or {@code SIGNED} (the transaction already carries the
 *     signature of the main wallet, which pays the fee)
 * @param side {@code BUY} spends {@code inputLamports}; {@code SELL} sells {@code inputBaseUnits}
 */
public record SwapSigningRequest(
        UUID signingRequestId,
        String transactionBase64,
        Mode mode,
        String side,
        Long inputLamports,
        Long inputBaseUnits,
        long quotedOut,
        long minOut,
        int maxSlippageBps,
        long feeLamports,
        // Review of the U4 (Minor 4): the token of the order; the TxSigner binds the route source and destination to
        // it.
        String mint) {

    /** Who signs: the bot wallet alone, or the main wallet first and then the bot wallet. */
    public enum Mode {
        SIGNED,
        AUTO_FULL
    }

    public SwapSigningRequest {
        Objects.requireNonNull(signingRequestId, "signingRequestId");
        Objects.requireNonNull(transactionBase64, "transactionBase64");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(mint, "mint");
    }
}
