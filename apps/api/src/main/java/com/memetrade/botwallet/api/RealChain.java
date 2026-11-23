package com.memetrade.botwallet.api;

import java.util.Objects;

/**
 * M3 for the REAL execution (U4, contract M3 {@code send}, {@code status}, {@code blockhash}): the Solana network
 * through the ChainWatcher of the bot wallet, in commitment finalized, over its two providers. Only the worker calls it,
 * and never inside a database transaction. Every method throws {@link RealUpstreamUnavailable} ({@code RPC}) when no
 * provider answered.
 */
public interface RealChain {

    /** The latest finalized blockhash and the last block height at which a transaction using it is valid. */
    Blockhash latestBlockhash();

    /** The current finalized block height (BR5.2: past {@code lastValidBlockHeight} and absent means FAILED). */
    long blockHeight();

    /** Sends a signed transaction (Base64); resending the same bytes is harmless. Returns the signature. */
    String send(String signedTransactionBase64);

    /** What the network says of a signature now. */
    TxStatus status(String txSignature);

    /**
     * BR6.1: the effect of a finalized swap on the bot wallet, read from the transaction: the change of its lamports,
     * of its balance of {@code mint} (all its token accounts), the fee of the transaction and whether the bot wallet
     * paid it.
     */
    SwapEffect swapEffect(String txSignature, String botWalletAddress, String mint);

    record Blockhash(String blockhash, long lastValidBlockHeight) {
        public Blockhash {
            Objects.requireNonNull(blockhash, "blockhash");
        }
    }

    /** {@code slippage} is set on a FAILED transaction whose error is the slippage check of the aggregator. */
    record TxStatus(Kind kind, boolean slippage) {
        public enum Kind {
            FINALIZED,
            FAILED,
            PENDING,
            NOT_FOUND
        }

        public static TxStatus of(Kind kind) {
            return new TxStatus(kind, false);
        }
    }

    /**
     * @param botLamportsDelta post minus pre balance of the bot wallet, fee and rent included
     * @param tokenBaseUnitsDelta post minus pre balance of {@code mint} owned by the bot wallet
     * @param feeLamports the fee of the transaction
     * @param feePaidByBot whether the bot wallet is the fee payer (AUTO_FULL)
     * @param slot 261121-paper-real-unico U2 (BR1.4, C13): the slot of the transaction, from the same getTransaction
     *     answer (no new call); empty when the answer did not carry it, and then nothing is recorded (SLOT_MISSING)
     */
    record SwapEffect(
            long botLamportsDelta,
            long tokenBaseUnitsDelta,
            long feeLamports,
            boolean feePaidByBot,
            java.util.OptionalLong slot) {

        public SwapEffect {
            Objects.requireNonNull(slot, "slot");
        }

        /** An effect whose slot is not known. */
        public SwapEffect(long botLamportsDelta, long tokenBaseUnitsDelta, long feeLamports, boolean feePaidByBot) {
            this(botLamportsDelta, tokenBaseUnitsDelta, feeLamports, feePaidByBot, java.util.OptionalLong.empty());
        }
    }
}
