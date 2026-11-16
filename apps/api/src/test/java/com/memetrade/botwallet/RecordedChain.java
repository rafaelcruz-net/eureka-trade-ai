package com.memetrade.botwallet;

import com.memetrade.botwallet.domain.ChainObservation;
import com.memetrade.botwallet.domain.ChainPort;
import com.memetrade.botwallet.domain.ChainUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Recorded network of the integration tests of the U5 (never a real RPC): balances, token accounts, the finalized slot,
 * the history of signatures of each address (newest first) and the signers of each transaction, set by the test. It can
 * be taken down; it counts the reads.
 */
public final class RecordedChain implements ChainPort {

    public final Map<String, Long> lamports = new ConcurrentHashMap<>();
    public final Map<String, Map<String, Long>> tokens = new ConcurrentHashMap<>();
    public final Map<String, List<SignatureInfo>> history = new ConcurrentHashMap<>();
    public final Map<String, List<String>> signers = new ConcurrentHashMap<>();
    public final AtomicInteger reads = new AtomicInteger();
    public final AtomicInteger signerReads = new AtomicInteger();
    public volatile boolean down;
    public volatile long slot = 1_000L;

    /** Adds a signature to the history of {@code address} as the newest one, signed by {@code signedBy}. */
    public RecordedChain transaction(String address, String signature, String... signedBy) {
        history.computeIfAbsent(address, a -> new ArrayList<>()).addFirst(new SignatureInfo(signature, false));
        signers.put(signature, List.of(signedBy));
        return this;
    }

    @Override
    public IncomingPage incomingSince(String address, String afterSignature, int limit) {
        guard();
        return new IncomingPage(List.of(), null);
    }

    @Override
    public Map<String, Long> balances(List<String> addresses) {
        guard();
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        addresses.forEach(address -> result.put(address, lamports.getOrDefault(address, 0L)));
        return result;
    }

    @Override
    public Map<String, List<ChainObservation>> checkSignatures(List<String> signatures) {
        guard();
        return Map.of();
    }

    @Override
    public RecentBlockhash latestBlockhash() {
        guard();
        return new RecentBlockhash("GHtXQBsoZHVnNFa9YevAzFr17DJjgHXk3ycTKD5xD3Zi", 1_000L);
    }

    @Override
    public String send(String signedTransactionBase64) {
        throw new IllegalStateException("the U5 never sends");
    }

    @Override
    public long rentExemptMinimum() {
        return 890_880L;
    }

    @Override
    public Map<String, Long> tokenBalances(String owner) {
        guard();
        return Map.copyOf(tokens.getOrDefault(owner, Map.of()));
    }

    @Override
    public long finalizedSlot() {
        guard();
        return slot;
    }

    @Override
    public SignaturePage signaturesSince(Purpose purpose, String address, String until, String before, int limit) {
        guard();
        List<SignatureInfo> newestFirst = history.getOrDefault(address, List.of());
        List<SignatureInfo> page = new ArrayList<>();
        boolean started = before == null;
        for (SignatureInfo info : newestFirst) {
            if (until != null && info.signature().equals(until)) {
                break;
            }
            if (!started) {
                started = info.signature().equals(before);
                continue;
            }
            page.add(info);
            if (page.size() == limit) {
                break;
            }
        }
        return new SignaturePage(page);
    }

    @Override
    public List<String> transactionSigners(String signature) {
        guard();
        signerReads.incrementAndGet();
        return signers.getOrDefault(signature, List.of());
    }

    private void guard() {
        reads.incrementAndGet();
        if (down) {
            throw new ChainUnavailableException("recorded chain down");
        }
    }
}
