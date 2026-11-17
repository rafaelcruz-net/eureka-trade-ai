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

    /**
     * What a call is for; each purpose has its own budget per provider (NFR3.7). 261113-trading-real-bot U5
     * (performance-design.md): the reconciliation and the restore check have their own quota and never take the one of
     * the withdrawals nor of the execution.
     */
    enum Purpose {
        DEPOSITS,
        WITHDRAWALS,
        RECONCILIATION,
        RESTORE_CHECK
    }

    /** U5: one finalized signature of the history of an address, and whether its transaction failed. */
    record SignatureInfo(String signature, boolean failed) {}

    /** U5: a page of the finalized history of an address, newest first. */
    record SignaturePage(List<SignatureInfo> signatures) {

        public SignaturePage {
            signatures = List.copyOf(signatures);
        }
    }

    /**
     * U5 (BR2.1): the base units of every mint held by {@code owner}, summed over all its token accounts of the Token
     * and the Token-2022 programs (two getTokenAccountsByOwner), in commitment finalized; an empty account counts zero.
     */
    Map<String, Long> tokenBalances(String owner);

    /**
     * 261113-trading-real-bot U7 (BR5.3b): the base units held by ONE token account (the custody account of a
     * conditional of the Jupiter, recorded at its creation), with its mint, in commitment finalized
     * (getTokenAccountBalance). An adapter without it fails as unavailable, so the reconciliation is UNAVAILABLE and
     * never a divergence.
     */
    default TokenAccountBalance tokenAccountBalance(String tokenAccount) {
        throw new ChainUnavailableException("The token account balance is not readable through this chain port");
    }

    /** U7: the balance of one token account and its mint. */
    record TokenAccountBalance(String mint, long baseUnits) {}

    /** U5 (BR2.1): the last finalized slot (getSlot finalized). */
    long finalizedSlot();

    /**
     * U5 (BR2.1, BR2.5): one page of the finalized history of {@code address}, newest first (getSignaturesForAddress):
     * strictly newer than {@code until} when given, strictly older than {@code before} when given, at most {@code
     * limit} (1 to 1.000).
     */
    SignaturePage signaturesSince(Purpose purpose, String address, String until, String before, int limit);

    /** U5 (BR2.5): the signers of a finalized transaction (getTransaction), in the order of the message. */
    List<String> transactionSigners(String signature);

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
