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

    /**
     * @param slot 261121-paper-real-unico U2 (BR1.4): the slot of the transaction read in the same getTransaction answer;
     *     empty when the answer did not carry it
     */
    record Effect(
            long ownerLamportsDelta,
            long tokenBaseUnitsDelta,
            long feeLamports,
            boolean feePaidByOwner,
            java.util.OptionalLong slot) {

        public Effect {
            java.util.Objects.requireNonNull(slot, "slot");
        }

        public Effect(long ownerLamportsDelta, long tokenBaseUnitsDelta, long feeLamports, boolean feePaidByOwner) {
            this(ownerLamportsDelta, tokenBaseUnitsDelta, feeLamports, feePaidByOwner, java.util.OptionalLong.empty());
        }
    }

    /**
     * The swap reads of {@code chain}: the ChainWatcher itself when it has them, or reads that are always unavailable
     * (no RPC configured). CI correction (run 43769144828): derived from the one ChainPort bean instead of being a
     * second bean, because the ChainWatcher is both a ChainPort and a SwapChain and two beans made both ambiguous.
     */
    static SwapChain of(ChainPort chain) {
        if (chain instanceof SwapChain swaps) {
            return swaps;
        }
        return new SwapChain() {
            @Override
            public long blockHeight() {
                throw new ChainUnavailableException("No RPC provider configured");
            }

            @Override
            public SignatureStatus status(String txSignature) {
                throw new ChainUnavailableException("No RPC provider configured");
            }

            @Override
            public Effect effect(String txSignature, String owner, String mint) {
                throw new ChainUnavailableException("No RPC provider configured");
            }
        };
    }
}
