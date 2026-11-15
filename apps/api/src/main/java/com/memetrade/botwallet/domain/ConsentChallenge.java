package com.memetrade.botwallet.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A consent challenge (entities.md ConsentChallenge, BR1.10): valid five minutes; locked IN_USE while the activation
 * waits for the TxSigner, back to PENDING if the TxSigner does not answer, consumed once.
 */
public record ConsentChallenge(
        UUID challengeId,
        UUID tenantId,
        UUID userId,
        ConsentKind kind,
        String message,
        String nonce,
        OperationMode mode,
        long capLamports,
        Instant issuedAt,
        Instant expiresAt,
        ChallengeState state,
        Instant inUseSince,
        Instant consumedAt) {

    public static ConsentChallenge issue(
            UUID tenantId,
            UUID userId,
            ConsentKind kind,
            String message,
            String nonce,
            OperationMode mode,
            long capLamports,
            Instant issuedAt) {
        Instant issued = issuedAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return new ConsentChallenge(
                UUID.randomUUID(),
                tenantId,
                userId,
                kind,
                message,
                nonce,
                mode,
                capLamports,
                issued,
                issued.plus(ConsentMessages.VALIDITY),
                ChallengeState.PENDING,
                null,
                null);
    }

    public boolean isUsableAt(Instant now) {
        return state == ChallengeState.PENDING && now.isBefore(expiresAt);
    }

    /** Locks the challenge for an activation; an expired one is CONSENT_EXPIRED, a used one CONSENT_INVALID. */
    public ConsentChallenge lock(Instant now) {
        if (state == ChallengeState.PENDING && !now.isBefore(expiresAt)) {
            throw new BotWalletException(BotWalletError.CONSENT_EXPIRED);
        }
        if (state != ChallengeState.PENDING) {
            throw new BotWalletException(BotWalletError.CONSENT_INVALID);
        }
        return with(ChallengeState.IN_USE, now, null);
    }

    public ConsentChallenge release() {
        return with(ChallengeState.PENDING, null, null);
    }

    public ConsentChallenge consume(Instant now) {
        if (state == ChallengeState.CONSUMED || state == ChallengeState.EXPIRED) {
            throw new BotWalletException(BotWalletError.CONSENT_INVALID);
        }
        return with(ChallengeState.CONSUMED, inUseSince, now);
    }

    private ConsentChallenge with(ChallengeState newState, Instant inUse, Instant consumed) {
        return new ConsentChallenge(
                challengeId,
                tenantId,
                userId,
                kind,
                message,
                nonce,
                mode,
                capLamports,
                issuedAt,
                expiresAt,
                newState,
                inUse,
                consumed);
    }
}
