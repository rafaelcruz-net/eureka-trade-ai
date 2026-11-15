package com.memetrade.botwallet.domain;

/**
 * 261113-trading-real-bot U4 (M3 for the REAL execution, BR5.1, BR5.2, BR6.1): the reads of the network that a swap
 * needs beyond the withdrawals, in commitment finalized, over the providers of the ChainWatcher. Every method throws
 * {@link ChainUnavailableException} when no provider answered.
 */
public interface SwapChain {

    /** The current finalized block height. */
    long blockHeight();

    /** The status of one signature on the first provider that answers. */
    SignatureStatus status(String txSignature);

    /** The effect of a finalized transaction on {@code owner}: lamports, balance of {@code mint}, fee and its payer. */
    Effect effect(String txSignature, String owner, String mint);

    enum SignatureStatus {
        FINALIZED,
        FAILED,
        FAILED_SLIPPAGE,
        PENDING,
        NOT_FOUND
    }

    record Effect(long ownerLamportsDelta, long tokenBaseUnitsDelta, long feeLamports, boolean feePaidByOwner) {}
}
