package com.memetrade.realexecution;

import com.memetrade.realexecution.domain.RouterQuote;
import com.memetrade.realexecution.domain.RouterUnavailableException;
import com.memetrade.realexecution.domain.SwapRouter;
import com.memetrade.realexecution.domain.solana.AccountRef;
import com.memetrade.realexecution.domain.solana.SwapInstruction;
import com.memetrade.shared.solana.Base58Codec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEST ONLY: the Jupiter of the integration tests of the U4. A quote of {@code expectedOut} for any input, and swap
 * instructions in the shape of the Jupiter (compute budget, a route of the bot wallet over synthetic pools). It can be
 * taken down, can return a route too large for one packet the first {@code tooLarge} times, and runs a hook while it
 * answers (to move the clock of the test).
 */
public final class FakeSwapRouter implements SwapRouter {

    public static final byte[] JUPITER = Base58Codec.decode("JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4", 32);
    public static final byte[] COMPUTE_BUDGET = Base58Codec.decode("ComputeBudget111111111111111111111111111111", 32);

    public volatile boolean down;
    /** The Jupiter answers a 4xx for the route (no route, mint refused): a refusal, never an outage. */
    public volatile boolean noRoute;

    public volatile long expectedOut = 1_000_000L;
    public volatile int tooLarge;
    public volatile Runnable whileAnswering = () -> {};
    public final AtomicInteger quotes = new AtomicInteger();
    public final AtomicInteger instructionCalls = new AtomicInteger();

    @Override
    public RouterQuote quote(String inputMint, String outputMint, long amount, int slippageBps, int maxAccounts) {
        quotes.incrementAndGet();
        if (down) {
            throw new RouterUnavailableException("fake Jupiter down");
        }
        if (noRoute) {
            throw new com.memetrade.realexecution.domain.NoRouteException("fake Jupiter: no route");
        }
        return new RouterQuote(inputMint, outputMint, amount, expectedOut, slippageBps, maxAccounts, "{}");
    }

    @Override
    public List<SwapInstruction> swapInstructions(RouterQuote quote, String userPublicKey) {
        instructionCalls.incrementAndGet();
        if (down) {
            throw new RouterUnavailableException("fake Jupiter down");
        }
        whileAnswering.run();
        byte[] bot = Base58Codec.decode(userPublicKey, 32);
        List<SwapInstruction> list = new ArrayList<>();
        list.add(new SwapInstruction(COMPUTE_BUDGET, List.of(), concat(new byte[] {2}, le(280_000, 4))));
        list.add(new SwapInstruction(COMPUTE_BUDGET, List.of(), concat(new byte[] {3}, le(12_345L, 8))));
        int pools = tooLarge-- > 0 ? 40 : 4;
        List<AccountRef> accounts = new ArrayList<>();
        accounts.add(new AccountRef(bot, true, true));
        for (int i = 0; i < pools; i++) {
            accounts.add(new AccountRef(filled(80 + i), false, i % 2 == 0));
        }
        ByteBuffer tail = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        tail.putLong(quote.inAmount())
                .putLong(quote.outAmount())
                .putShort((short) quote.slippageBps())
                .put((byte) 0);
        list.add(new SwapInstruction(JUPITER, accounts, concat(new byte[8], tail.array())));
        return list;
    }

    @Override
    public void probe() {
        if (down) {
            throw new RouterUnavailableException("fake Jupiter down");
        }
    }

    public void reset() {
        down = false;
        noRoute = false;
        expectedOut = 1_000_000L;
        tooLarge = 0;
        whileAnswering = () -> {};
        quotes.set(0);
        instructionCalls.set(0);
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static byte[] le(long value, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (value >>> (8 * i));
        }
        return bytes;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }
}
