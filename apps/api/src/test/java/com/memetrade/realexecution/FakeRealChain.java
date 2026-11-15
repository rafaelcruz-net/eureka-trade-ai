package com.memetrade.realexecution;

import com.memetrade.botwallet.api.RealChain;
import com.memetrade.botwallet.api.RealUpstreamUnavailable;
import com.memetrade.realexecution.domain.solana.WireTransaction;
import com.memetrade.testsupport.SyntheticIds;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TEST ONLY: the Solana network of the integration tests of the U4 (M3). Every send is recorded by its signature (the
 * first signature of the transaction, like the network); a signature is PENDING once sent and the test finalizes it,
 * fails it or makes it vanish. It can be taken down (RPC unavailable). Never a real network (team.md).
 */
public final class FakeRealChain implements RealChain {

    public static final String BLOCKHASH = SyntheticIds.encode(filled(9));

    private final Map<String, AtomicInteger> sends = new ConcurrentHashMap<>();
    private final Map<String, TxStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, SwapEffect> effects = new ConcurrentHashMap<>();
    private final AtomicInteger totalSends = new AtomicInteger();
    public final AtomicLong height = new AtomicLong(1_000);
    public volatile boolean down;
    /** The send reaches the provider but the answer is lost (crash or timeout after the network accepted it). */
    public volatile boolean loseSendAnswer;

    public volatile SwapEffect defaultEffect = new SwapEffect(-50_008_457L, 1_000_000L, 8_457L, true);
    /**
     * Like the network, each ask gives the blockhash of a newer slot; true repeats {@link #BLOCKHASH} on every ask, so
     * two orders with the same quote build byte for byte the same transaction.
     */
    public volatile boolean sameBlockhash;

    private final AtomicInteger blockhashes = new AtomicInteger();

    @Override
    public Blockhash latestBlockhash() {
        guard();
        if (sameBlockhash) {
            return new Blockhash(BLOCKHASH, height.get() + 150);
        }
        byte[] hash = filled(9);
        int n = blockhashes.incrementAndGet();
        hash[0] = (byte) n;
        hash[1] = (byte) (n >>> 8);
        hash[2] = (byte) (n >>> 16);
        return new Blockhash(SyntheticIds.encode(hash), height.get() + 150);
    }

    @Override
    public long blockHeight() {
        guard();
        return height.get();
    }

    @Override
    public String send(String signedTransactionBase64) {
        guard();
        WireTransaction wire = WireTransaction.decodeBase64(signedTransactionBase64);
        String signature = SyntheticIds.encode(wire.signatures().getFirst());
        sends.computeIfAbsent(signature, s -> new AtomicInteger()).incrementAndGet();
        totalSends.incrementAndGet();
        statuses.putIfAbsent(signature, TxStatus.of(TxStatus.Kind.PENDING));
        if (loseSendAnswer) {
            throw new RealUpstreamUnavailable(RealUpstreamUnavailable.Provider.RPC);
        }
        return signature;
    }

    @Override
    public TxStatus status(String txSignature) {
        guard();
        return statuses.getOrDefault(txSignature, TxStatus.of(TxStatus.Kind.NOT_FOUND));
    }

    @Override
    public SwapEffect swapEffect(String txSignature, String botWalletAddress, String mint) {
        guard();
        return effects.getOrDefault(txSignature, defaultEffect);
    }

    public void finalize(String signature) {
        statuses.put(signature, TxStatus.of(TxStatus.Kind.FINALIZED));
    }

    public void fail(String signature, boolean slippage) {
        statuses.put(signature, new TxStatus(TxStatus.Kind.FAILED, slippage));
    }

    public void effect(String signature, SwapEffect effect) {
        effects.put(signature, effect);
    }

    public int sends(String signature) {
        AtomicInteger count = sends.get(signature);
        return count == null ? 0 : count.get();
    }

    public int totalSends() {
        return totalSends.get();
    }

    public void reset() {
        sends.clear();
        statuses.clear();
        effects.clear();
        totalSends.set(0);
        down = false;
        loseSendAnswer = false;
        sameBlockhash = false;
        height.set(1_000);
        defaultEffect = new SwapEffect(-50_008_457L, 1_000_000L, 8_457L, true);
    }

    private void guard() {
        if (down) {
            throw new RealUpstreamUnavailable(RealUpstreamUnavailable.Provider.RPC);
        }
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
