package com.memetrade.botwallet;

import com.memetrade.botwallet.api.SwapSigningRequest;
import com.memetrade.botwallet.domain.ConsentKind;
import com.memetrade.botwallet.domain.TxSignerPort;
import com.memetrade.realexecution.domain.solana.WireTransaction;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestWallet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TxSigner of the integration tests of the api (unit-test-instructions.md "TxSigner falso"): idempotent by request id
 * like the real one, can be taken down, and refuses a consent on demand. Bot wallet addresses are generated in test
 * code only.
 *
 * <p>261113-trading-real-bot U4: it also signs swaps in process like the TxSigner, with the test key of a bot wallet
 * registered by the test ({@link #register}): the bot signs its slot (the first in AUTO_FULL, the second in SIGNED),
 * the same signingRequestId returns the same answer and another content is refused (REQUEST_MISMATCH). The content
 * policy itself is tested in {@code apps/txsigner}.
 */
public final class FakeTxSigner implements TxSignerPort {

    private final Map<UUID, String> wallets = new ConcurrentHashMap<>();
    private final Map<String, TestWallet> botKeys = new ConcurrentHashMap<>();
    private final Map<UUID, SignOutcome> swaps = new ConcurrentHashMap<>();
    private final Map<UUID, String> swapContents = new ConcurrentHashMap<>();
    public volatile boolean down;
    public volatile String refusal;
    public volatile String swapRefusal;
    /** Runs while a swap is being signed (to take the lease of the order from the test, review Minor 3). */
    public volatile Runnable whileSigning = () -> {};

    public final Map<UUID, Long> lowered = new ConcurrentHashMap<>();
    public final Map<String, String> raisedModes = new ConcurrentHashMap<>();
    public final AtomicInteger swapSignatures = new AtomicInteger();
    public final AtomicInteger swapCalls = new AtomicInteger();

    @Override
    public String createWallet(UUID requestId, String main, String message, String signature) {
        guard();
        return wallets.computeIfAbsent(requestId, id -> TestWallet.generate().address());
    }

    @Override
    public void lowerCap(UUID requestId, String botWalletAddress, long capLamports) {
        guard();
        lowered.put(requestId, capLamports);
    }

    @Override
    public void lowerMode(UUID requestId, String botWalletAddress, String mode) {
        guard();
        raisedModes.put(botWalletAddress, mode);
    }

    @Override
    public void raise(UUID requestId, String botWalletAddress, ConsentKind kind, String message, String signature) {
        guard();
        raisedModes.put(botWalletAddress, kind.name());
    }

    @Override
    public SignOutcome signWithdrawal(UUID id, String bot, String transaction, long lamports, long fee) {
        guard();
        return new SignOutcome("Sig" + id, "c2lnbmVk", null);
    }

    /** Registers the test key of a bot wallet, so swaps of that wallet are signed with it. */
    public void register(TestWallet bot) {
        botKeys.put(bot.address(), bot);
    }

    @Override
    public SignOutcome signSwap(String botWalletAddress, SwapSigningRequest request) {
        swapCalls.incrementAndGet();
        if (down) {
            throw new SignerUnavailableException("fake TxSigner down");
        }
        whileSigning.run();
        String previous = swapContents.putIfAbsent(request.signingRequestId(), messageOf(request));
        if (previous != null && !previous.equals(messageOf(request))) {
            return new SignOutcome(null, null, "REQUEST_MISMATCH");
        }
        return swaps.computeIfAbsent(request.signingRequestId(), id -> sign(botWalletAddress, request));
    }

    private SignOutcome sign(String botWalletAddress, SwapSigningRequest request) {
        if (swapRefusal != null) {
            return new SignOutcome(null, null, swapRefusal);
        }
        TestWallet bot = botKeys.get(botWalletAddress);
        if (bot == null) {
            return new SignOutcome(null, null, "WALLET_NOT_FOUND");
        }
        WireTransaction transaction = WireTransaction.decodeBase64(request.transactionBase64());
        int slot = request.mode() == SwapSigningRequest.Mode.SIGNED ? 1 : 0;
        WireTransaction signed = transaction.withSignature(slot, bot.signBytes(transaction.messageBytes()));
        swapSignatures.incrementAndGet();
        return new SignOutcome(SyntheticIds.encode(signed.signatures().getFirst()), signed.toBase64(), null);
    }

    private static String messageOf(SwapSigningRequest request) {
        return java.util.Base64.getEncoder()
                .encodeToString(WireTransaction.decodeBase64(request.transactionBase64())
                        .messageBytes());
    }

    @Override
    public boolean reachable() {
        return !down;
    }

    public int walletsCreated() {
        return wallets.size();
    }

    public void reset() {
        down = false;
        whileSigning = () -> {};
        refusal = null;
        swapRefusal = null;
        swaps.clear();
        swapContents.clear();
        swapSignatures.set(0);
        swapCalls.set(0);
        raisedModes.clear();
    }

    private void guard() {
        if (down) {
            throw new SignerUnavailableException("fake TxSigner down");
        }
        if (refusal != null) {
            throw new SignerRefusedException(refusal);
        }
    }
}
