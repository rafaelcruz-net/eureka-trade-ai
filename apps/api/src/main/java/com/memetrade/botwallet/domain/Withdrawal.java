package com.memetrade.botwallet.domain;

import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealEntryKind;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A withdrawal to the registered main wallet (BR4.7, functional-spec.md): REQUESTED reserves the amount plus the fee;
 * SIGNED keeps the signed transaction, which every resend reuses (never a second signature); SENT; CONFIRMED in
 * finalized writes the output, the real fee and the release of the reserve; a refusal of the TxSigner or an expired
 * blockhash seen by BOTH providers fails and only releases the reserve; 60 s without a result makes it AMBIGUOUS.
 * The technical columns attempts and nextAttemptAt drive the growing wait of 5 s doubling up to 5 min (NFR4.2).
 */
public record Withdrawal(
        UUID withdrawalId,
        UUID tenantId,
        UUID botWalletId,
        WithdrawalKind kind,
        long lamports,
        long feeLamports,
        WithdrawalState state,
        UUID idempotencyKey,
        UUID signingRequestId,
        String blockhash,
        Long lastValidHeight,
        String signedTransaction,
        String messageDigest,
        String txSignature,
        String failureCode,
        int attempts,
        Instant nextAttemptAt,
        Instant requestedAt,
        Instant updatedAt) {

    static final Duration AMBIGUOUS_AFTER = Duration.ofSeconds(60);
    static final Duration UNSIGNED_LIMIT = Duration.ofHours(24);
    static final Duration FIRST_WAIT = Duration.ofSeconds(5);
    static final Duration LONGEST_WAIT = Duration.ofMinutes(5);

    public static WithdrawalTransition request(
            UUID tenantId, UUID botWalletId, WithdrawalAmount amount, UUID idempotencyKey, Instant now) {
        Withdrawal withdrawal = new Withdrawal(
                UUID.randomUUID(),
                tenantId,
                botWalletId,
                amount.kind(),
                amount.lamports(),
                amount.feeLamports(),
                WithdrawalState.REQUESTED,
                idempotencyKey,
                UUID.randomUUID(),
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                now,
                now,
                now);
        return new WithdrawalTransition(
                withdrawal, List.of(withdrawal.entry(RealEntryKind.RESERVE, amount.reservedLamports(), "reserve")));
    }

    public String reference() {
        return "wd:" + withdrawalId;
    }

    public boolean needsSignature() {
        return state == WithdrawalState.REQUESTED;
    }

    public Withdrawal signed(String signedTx, String digest, String hash, long validHeight, Instant now) {
        return signed(signedTx, digest, hash, validHeight, txSignature, now);
    }

    /** SIGNED with the signature returned by the TxSigner, which the follow-up checks on the network. */
    public Withdrawal signed(
            String signedTx, String digest, String hash, long validHeight, String signature, Instant now) {
        require(WithdrawalState.REQUESTED);
        return copy(WithdrawalState.SIGNED, hash, validHeight, signedTx, digest, signature, failureCode, now);
    }

    /**
     * Keeps the blockhash BEFORE asking the TxSigner, so a retry after a lost answer asks for the same message with the
     * same signingRequestId and gets the same signature back (never a second transaction).
     */
    public Withdrawal withBlockhash(String hash, long validHeight, Instant now) {
        require(WithdrawalState.REQUESTED);
        return copy(state, hash, validHeight, signedTransaction, messageDigest, txSignature, failureCode, now);
    }

    /** Next look on the network without counting an attempt nor moving updatedAt (the 60 s rule reads it). */
    public Withdrawal pollAgainAt(Instant at) {
        return new Withdrawal(
                withdrawalId,
                tenantId,
                botWalletId,
                kind,
                lamports,
                feeLamports,
                state,
                idempotencyKey,
                signingRequestId,
                blockhash,
                lastValidHeight,
                signedTransaction,
                messageDigest,
                txSignature,
                failureCode,
                attempts,
                at,
                requestedAt,
                updatedAt);
    }

    public Withdrawal sent(Instant now) {
        if (state != WithdrawalState.SIGNED && state != WithdrawalState.SENT && state != WithdrawalState.AMBIGUOUS) {
            throw new IllegalStateException("A " + state + " withdrawal is not sent");
        }
        WithdrawalState next = state == WithdrawalState.AMBIGUOUS ? WithdrawalState.AMBIGUOUS : WithdrawalState.SENT;
        return copy(next, blockhash, lastValidHeight, signedTransaction, messageDigest, txSignature, failureCode, now);
    }

    public WithdrawalTransition confirmed(String signature, long realFeeLamports, Instant now) {
        return confirmed(signature, realFeeLamports, java.util.OptionalLong.empty(), now);
    }

    /**
     * 261121-paper-real-unico U2 (BR1.4): the confirmation with the slot of its signature, read with its status; the
     * store records the slot with the entries.
     */
    public WithdrawalTransition confirmed(
            String signature, long realFeeLamports, java.util.OptionalLong slot, Instant now) {
        if (state.isFinal() || state == WithdrawalState.REQUESTED) {
            throw new IllegalStateException("A " + state + " withdrawal cannot be confirmed");
        }
        Withdrawal done = copy(
                WithdrawalState.CONFIRMED,
                blockhash,
                lastValidHeight,
                signedTransaction,
                messageDigest,
                signature,
                null,
                now);
        List<RealEntry> entries = new ArrayList<>();
        entries.add(entry(RealEntryKind.WITHDRAWAL, Math.negateExact(lamports), "out"));
        if (realFeeLamports > 0) {
            entries.add(entry(RealEntryKind.NETWORK_FEE, Math.negateExact(realFeeLamports), "fee"));
        }
        entries.add(entry(RealEntryKind.RESERVE_RELEASE, Math.addExact(lamports, feeLamports), "release"));
        return new WithdrawalTransition(done, entries, slot);
    }

    public WithdrawalTransition refusedBySigner(String code, Instant now) {
        require(WithdrawalState.REQUESTED);
        return failed(code, now);
    }

    /** SENT for 60 s without a result becomes AMBIGUOUS; it is then checked on the network until it ends. */
    public Withdrawal withoutResult(Instant now) {
        if (state == WithdrawalState.SENT && !now.isBefore(updatedAt.plus(AMBIGUOUS_AFTER))) {
            return copy(
                    WithdrawalState.AMBIGUOUS,
                    blockhash,
                    lastValidHeight,
                    signedTransaction,
                    messageDigest,
                    txSignature,
                    failureCode,
                    now);
        }
        return this;
    }

    /**
     * Result of the check on the two providers: finalized on either confirms; FAILED only when both answered, both did
     * not find the signature and both report a finalized height above lastValidHeight; otherwise nothing changes.
     */
    public WithdrawalTransition afterChainCheck(ChainObservation first, ChainObservation second, Instant now) {
        for (ChainObservation observation : List.of(first, second)) {
            if (observation.status() == ChainObservation.Status.FINALIZED) {
                return confirmed(observation.txSignature(), observation.feeLamports(), observation.slot(), now);
            }
        }
        if (expired(first) && expired(second)) {
            return failed("BLOCKHASH_EXPIRED", now);
        }
        return WithdrawalTransition.unchanged(this);
    }

    /** NFR4.10: 24 hours without a signature fail the withdrawal and release the reserve. */
    public WithdrawalTransition expireIfUnsigned(Instant now) {
        if (state == WithdrawalState.REQUESTED && !now.isBefore(requestedAt.plus(UNSIGNED_LIMIT))) {
            return failed("UNSIGNED_EXPIRED", now);
        }
        return WithdrawalTransition.unchanged(this);
    }

    public Withdrawal retryLater(Instant now) {
        int next = attempts + 1;
        Duration wait = FIRST_WAIT.multipliedBy(1L << Math.min(next - 1, 10));
        Instant at = now.plus(wait.compareTo(LONGEST_WAIT) > 0 ? LONGEST_WAIT : wait);
        return new Withdrawal(
                withdrawalId,
                tenantId,
                botWalletId,
                kind,
                lamports,
                feeLamports,
                state,
                idempotencyKey,
                signingRequestId,
                blockhash,
                lastValidHeight,
                signedTransaction,
                messageDigest,
                txSignature,
                failureCode,
                next,
                at,
                requestedAt,
                now);
    }

    private boolean expired(ChainObservation observation) {
        return observation.status() == ChainObservation.Status.NOT_FOUND
                && lastValidHeight != null
                && observation.finalizedHeight() > lastValidHeight;
    }

    private WithdrawalTransition failed(String code, Instant now) {
        Withdrawal failed = copy(
                WithdrawalState.FAILED,
                blockhash,
                lastValidHeight,
                signedTransaction,
                messageDigest,
                txSignature,
                code,
                now);
        return new WithdrawalTransition(
                failed, List.of(entry(RealEntryKind.RESERVE_RELEASE, Math.addExact(lamports, feeLamports), "release")));
    }

    private RealEntry entry(RealEntryKind entryKind, long amount, String suffix) {
        String key = reference() + ":" + suffix;
        return new RealEntry(tenantId, botWalletId, entryKind, amount, null, reference(), key);
    }

    private void require(WithdrawalState expected) {
        if (state != expected) {
            throw new IllegalStateException("Expected a " + expected + " withdrawal, found " + state);
        }
    }

    private Withdrawal copy(
            WithdrawalState newState,
            String hash,
            Long validHeight,
            String signedTx,
            String digest,
            String signature,
            String failure,
            Instant now) {
        return new Withdrawal(
                withdrawalId,
                tenantId,
                botWalletId,
                kind,
                lamports,
                feeLamports,
                newState,
                idempotencyKey,
                signingRequestId,
                hash,
                validHeight,
                signedTx,
                digest,
                signature,
                failure,
                attempts,
                nextAttemptAt,
                requestedAt,
                now);
    }
}
