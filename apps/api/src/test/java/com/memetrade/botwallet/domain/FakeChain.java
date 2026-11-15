package com.memetrade.botwallet.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Recorded network of the worker tests (never a real RPC): scripted pages, statuses and a blockhash. */
final class FakeChain implements ChainPort {

    final List<String> sent = new ArrayList<>();
    final Map<String, List<ChainObservation>> statuses = new LinkedHashMap<>();
    final Map<String, IncomingPage> pages = new LinkedHashMap<>();
    boolean down;
    int blockhashCalls;

    @Override
    public IncomingPage incomingSince(String address, String afterSignature, int limit) {
        failIfDown();
        return pages.getOrDefault(address + "@" + afterSignature, new IncomingPage(List.of(), null));
    }

    @Override
    public Map<String, Long> balances(List<String> addresses) {
        failIfDown();
        return Map.of();
    }

    @Override
    public Map<String, List<ChainObservation>> checkSignatures(List<String> signatures) {
        failIfDown();
        Map<String, List<ChainObservation>> result = new LinkedHashMap<>();
        signatures.forEach(s -> result.put(
                s, statuses.getOrDefault(s, List.of(ChainObservation.unavailable(), ChainObservation.unavailable()))));
        return result;
    }

    @Override
    public RecentBlockhash latestBlockhash() {
        failIfDown();
        blockhashCalls++;
        return new RecentBlockhash("GHtXQBsoZHVnNFa9YevAzFr17DJjgHXk3ycTKD5xD3Zi", 1_000L);
    }

    @Override
    public String send(String signedTransactionBase64) {
        failIfDown();
        sent.add(signedTransactionBase64);
        return "sent";
    }

    @Override
    public long rentExemptMinimum() {
        return 890_880L;
    }

    private void failIfDown() {
        if (down) {
            throw new ChainUnavailableException("fake chain down");
        }
    }
}
