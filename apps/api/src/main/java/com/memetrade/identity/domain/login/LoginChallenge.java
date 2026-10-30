package com.memetrade.identity.domain.login;

import java.time.Instant;

/** Single-use login challenge (entities.md LoginChallenge). The nonce is public in the message and not a secret. */
public record LoginChallenge(
        String nonce,
        String walletAddress,
        String domain,
        String chain,
        String message,
        Instant issuedAt,
        Instant expiresAt,
        ChallengeState state,
        Instant consumedAt) {

    public boolean isExpiredAt(Instant now) {
        return now.isAfter(expiresAt);
    }
}
