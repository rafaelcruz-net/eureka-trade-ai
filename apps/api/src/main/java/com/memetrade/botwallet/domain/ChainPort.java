package com.memetrade.botwallet.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Port of the Solana network (M3 ChainWatcher, reliability-design.md): only the worker calls it. Every read is in
 * commitment finalized. A withdrawal is checked on BOTH providers, because FAILED needs both to agree; the rest uses the
 * first provider that answers. The RPC budget is split 80% for deposits and 20% for withdrawals (NFR3.7).
 */
public interface ChainPort {

    enum Purpose {
        DEPOSITS,
        WITHDRAWALS
    }

    /** One finalized incoming SOL transfer to the bot wallet. */
    record IncomingTransfer(String txSignature, String sourceAddress, long lamports, Instant finalizedAt) {}

    record RecentBlockhash(String blockhash, long lastValidBlockHeight) {}

    /**
     * A page of the finalized history of an address after the cursor: the incoming transfers, oldest first, and the
     * last signature read (incoming or not), which becomes the new cursor; null when nothing new.
     */
    record IncomingPage(List<IncomingTransfer> transfers, String lastSignature) {}

    /** Finalized history of {@code address} after {@code afterSignature} (exclusive), oldest first. */
    IncomingPage incomingSince(String address, String afterSignature, int limit);

    /** Finalized balances in lamports, one call for up to 100 addresses (getMultipleAccounts). */
    Map<String, Long> balances(List<String> addresses);

    /** For each signature, what each of the two providers says (always two observations, in provider order). */
    Map<String, List<ChainObservation>> checkSignatures(List<String> signatures);

    RecentBlockhash latestBlockhash();

    /** Sends a signed transaction (Base64) and returns its signature; resending the same bytes is harmless. */
    String send(String signedTransactionBase64);

    long rentExemptMinimum();
}
