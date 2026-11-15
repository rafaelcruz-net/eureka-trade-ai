package com.memetrade.txsigner.policy;

import java.time.Instant;

/** A consent that passed every check; its nonce still has to be recorded together with the policy change (BR1.8). */
public record VerifiedConsent(
        ConsentPurpose purpose,
        OperationMode mode,
        long capLamports,
        String nonce,
        Instant expiresAt,
        String messageDigest) {}
