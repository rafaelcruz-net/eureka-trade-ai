package com.memetrade.txsigner.domain;

import com.memetrade.txsigner.policy.OperationMode;
import java.time.Instant;
import java.util.UUID;

/**
 * One applied change of a WalletPolicy, kept for the idempotency of {@code POST /internal/v1/wallets/{addr}/consents}
 * by requestId: a repetition returns the same result and never applies the change twice.
 */
public record PolicyChange(
        UUID requestId,
        String botWalletAddress,
        Kind kind,
        long capLamports,
        OperationMode mode,
        String consentDigest,
        Instant decidedAt) {

    public enum Kind {
        RAISE_MODE,
        RAISE_CAP,
        LOWER_MODE,
        LOWER_CAP,
        CLOSE;

        public boolean isRaise() {
            return this == RAISE_MODE || this == RAISE_CAP;
        }
    }
}
