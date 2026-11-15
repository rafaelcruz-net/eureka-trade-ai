package com.memetrade.realexecution.domain;

import com.memetrade.realexecution.domain.solana.SwapInstruction;
import java.util.List;

/**
 * X1 (contract-summary.md, owner U4): the swap aggregator, the Jupiter in production ({@code infra.jupiter}) and a
 * stub with recorded answers in the local Compose. Only the worker calls it, never inside a database transaction.
 * Every method throws {@link RouterUnavailableException} on a technical failure (timeout, 5xx, open circuit).
 */
public interface SwapRouter {

    /** A quote of {@code amount} of {@code inputMint} for {@code outputMint} with the slippage of the person. */
    RouterQuote quote(String inputMint, String outputMint, long amount, int slippageBps, int maxAccounts);

    /**
     * The instructions of the swap of {@code quote} for the bot wallet ({@code userPublicKey}): compute budget, setup,
     * the route and the cleanup, in order. The server builds the message from them (static keys only).
     */
    List<SwapInstruction> swapInstructions(RouterQuote quote, String userPublicKey);

    /** BR9.1: the call of health (a small quote); throws when the provider does not answer. */
    void probe();
}
