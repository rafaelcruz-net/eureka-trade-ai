package com.memetrade.realexecution.domain;

import java.util.Objects;

/**
 * A quote of the aggregator (X1): input and output in base units (lamports for SOL), the slippage asked and the
 * {@code maxAccounts} of the route. {@code rawQuote} is the answer the aggregator needs back to build the swap; it is
 * never logged.
 */
public record RouterQuote(
        String inputMint,
        String outputMint,
        long inAmount,
        long outAmount,
        int slippageBps,
        int maxAccounts,
        String rawQuote) {

    public RouterQuote {
        Objects.requireNonNull(inputMint, "inputMint");
        Objects.requireNonNull(outputMint, "outputMint");
        Objects.requireNonNull(rawQuote, "rawQuote");
        if (inAmount < 1 || outAmount < 0) {
            throw new IllegalArgumentException(
                    "A quote spends at least one base unit and never gives a negative output");
        }
    }
}
