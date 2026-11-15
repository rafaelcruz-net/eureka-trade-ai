package com.memetrade.txsigner.policy;

/**
 * Context of a SWAP sent by the BotWallet (contract R1 addendum of the U4, entities.md SwapSigningRequest). It can
 * only restrict: the transaction must agree with it and with the TxSigner's own policy. Every field is required for its
 * side; a missing one refuses the signature ({@code CONTEXT_MISSING}).
 *
 * @param inputLamports lamports spent by a BUY
 * @param inputBaseUnits base units sold by a SELL
 * @param minOut the smallest output the route must enforce on chain
 * @param feeLamports the fee of the transaction calculated before signing (base fee per signature plus priority)
 * @param mint the token of the order (Base58): bought by a BUY, sold by a SELL; the route source and destination are
 *     bound to it (review of the U4, Minor 4)
 */
public record SwapContext(
        SwapMode mode,
        SwapSide side,
        Long inputLamports,
        Long inputBaseUnits,
        Long quotedOut,
        Long minOut,
        Integer maxSlippageBps,
        Long feeLamports,
        String mint) {

    /** Every field the side needs is present and not negative. */
    public boolean complete() {
        if (mode == null
                || side == null
                || mint == null
                || quotedOut == null
                || minOut == null
                || maxSlippageBps == null
                || feeLamports == null) {
            return false;
        }
        if (quotedOut < 0 || minOut < 0 || feeLamports < 0) {
            return false;
        }
        Long input = side == SwapSide.BUY ? inputLamports : inputBaseUnits;
        return input != null && input > 0;
    }

    public long input() {
        return side == SwapSide.BUY ? inputLamports : inputBaseUnits;
    }
}
