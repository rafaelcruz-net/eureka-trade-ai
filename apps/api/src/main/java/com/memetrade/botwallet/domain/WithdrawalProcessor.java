package com.memetrade.botwallet.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Withdrawal follow-up of the worker (BR4.7, NFR4.2, NFR4.6, NFR4.10). REQUESTED: keeps a blockhash, builds the exact
 * transfer to the registered main wallet and asks the TxSigner with the withdrawal's signingRequestId; SIGNED: sends;
 * SENT and AMBIGUOUS: one batched check on both providers. A resend uses only the stored signed transaction. Outages of
 * the TxSigner or the network leave the withdrawal where it was, with a growing wait; everything restarts from the
 * stored states after a restart.
 */
public final class WithdrawalProcessor {

    static final Duration POLL = Duration.ofSeconds(2);

    private final InstantSource clock;
    private final WithdrawalStore store;
    private final TxSignerPort signer;
    private final ChainPort chain;
    private final WithdrawalSettings settings;

    public WithdrawalProcessor(
            InstantSource clock,
            WithdrawalStore store,
            TxSignerPort signer,
            ChainPort chain,
            WithdrawalSettings settings) {
        this.clock = clock;
        this.store = store;
        this.signer = signer;
        this.chain = chain;
        this.settings = settings;
    }

    /** One cycle; returns how many withdrawals were looked at. */
    public int processReady() {
        Instant now = clock.instant();
        List<Withdrawal> ready = store.claimReady(now, settings.lease(), settings.batchSize());
        List<Withdrawal> onChain = new ArrayList<>();
        for (Withdrawal withdrawal : ready) {
            switch (withdrawal.state()) {
                case REQUESTED -> sign(withdrawal, now);
                case SIGNED -> send(withdrawal, now);
                case SENT, AMBIGUOUS -> onChain.add(withdrawal);
                default -> {
                    // CONFIRMED and FAILED are never claimed.
                }
            }
        }
        check(onChain, now);
        return ready.size();
    }

    private void sign(Withdrawal withdrawal, Instant now) {
        WithdrawalTransition expired = withdrawal.expireIfUnsigned(now);
        if (expired.withdrawal().state() == WithdrawalState.FAILED) {
            store.save(expired);
            return;
        }
        Optional<WithdrawalStore.WithdrawalTarget> target = store.target(withdrawal);
        if (target.isEmpty()) {
            store.save(withdrawal.refusedBySigner("WALLET_NOT_FOUND", now));
            return;
        }
        Withdrawal current = withdrawal;
        try {
            if (current.blockhash() == null) {
                ChainPort.RecentBlockhash recent = chain.latestBlockhash();
                current = current.withBlockhash(recent.blockhash(), recent.lastValidBlockHeight(), now);
                store.save(WithdrawalTransition.unchanged(current));
            }
            String transaction = TransferMessage.unsignedBase64(
                    target.get().botWalletAddress(),
                    target.get().mainWalletAddress(),
                    current.lamports(),
                    settings.computeUnitLimit(),
                    settings.computeUnitPrice(),
                    current.blockhash());
            TxSignerPort.SignOutcome outcome = signer.signWithdrawal(
                    current.signingRequestId(),
                    target.get().botWalletAddress(),
                    transaction,
                    current.lamports(),
                    current.feeLamports());
            if (!outcome.signed()) {
                store.save(current.refusedBySigner(outcome.refusal(), now));
                return;
            }
            Withdrawal signed = current.signed(
                    outcome.signedTransactionBase64(),
                    messageDigest(transaction),
                    current.blockhash(),
                    current.lastValidHeight(),
                    outcome.signature(),
                    now);
            store.save(WithdrawalTransition.unchanged(signed));
            send(signed, now);
        } catch (TxSignerPort.SignerUnavailableException | ChainUnavailableException outage) {
            store.save(WithdrawalTransition.unchanged(current.retryLater(now)));
        }
    }

    private void send(Withdrawal withdrawal, Instant now) {
        try {
            chain.send(withdrawal.signedTransaction());
            store.save(WithdrawalTransition.unchanged(withdrawal.sent(now).pollAgainAt(now.plus(POLL))));
        } catch (ChainUnavailableException outage) {
            store.save(WithdrawalTransition.unchanged(withdrawal.retryLater(now)));
        }
    }

    private void check(List<Withdrawal> withdrawals, Instant now) {
        if (withdrawals.isEmpty()) {
            return;
        }
        Map<String, List<ChainObservation>> statuses;
        try {
            statuses = chain.checkSignatures(
                    withdrawals.stream().map(Withdrawal::txSignature).toList());
        } catch (ChainUnavailableException outage) {
            withdrawals.forEach(w -> store.save(WithdrawalTransition.unchanged(w.pollAgainAt(now.plus(POLL)))));
            return;
        }
        for (Withdrawal withdrawal : withdrawals) {
            List<ChainObservation> seen = statuses.getOrDefault(
                    withdrawal.txSignature(), List.of(ChainObservation.unavailable(), ChainObservation.unavailable()));
            WithdrawalTransition after = withdrawal.afterChainCheck(seen.get(0), seen.get(1), now);
            if (after.withdrawal().state() != withdrawal.state()) {
                store.save(after);
                continue;
            }
            Withdrawal next = withdrawal.withoutResult(now);
            if (next.state() == WithdrawalState.AMBIGUOUS) {
                resend(next);
            }
            store.save(WithdrawalTransition.unchanged(next.pollAgainAt(now.plus(POLL))));
        }
    }

    /** Resending the SAME signed bytes is harmless: the network accepts one transaction per signature. */
    private void resend(Withdrawal withdrawal) {
        try {
            chain.send(withdrawal.signedTransaction());
        } catch (ChainUnavailableException outage) {
            // The next cycle tries again; the withdrawal stays AMBIGUOUS until the network answers.
        }
    }

    /** SHA-256 of the message bytes (the wire minus the signature slot). */
    static String messageDigest(String unsignedBase64) {
        byte[] wire = Base64.getDecoder().decode(unsignedBase64.getBytes(StandardCharsets.US_ASCII));
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(Arrays.copyOfRange(wire, 65, wire.length)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }
}
