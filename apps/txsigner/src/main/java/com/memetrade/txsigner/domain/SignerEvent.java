package com.memetrade.txsigner.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit event of the TxSigner (BR6.1, NFR1.7): one per decision. It carries the bot wallet address in its own field
 * (masked in logs) and NEVER the main wallet, a consent text, a transaction nor a key reference.
 */
public record SignerEvent(
        Type type, String botWalletAddress, UUID requestId, String operation, String reason, Instant occurredAt) {

    public enum Type {
        WALLET_CREATED,
        WALLET_CREATION_REFUSED,
        POLICY_CHANGED,
        POLICY_CHANGE_REFUSED,
        SIGNED,
        SIGNING_REFUSED,
        REQUEST_MISMATCH,
        WALLET_RESTORED,
        WALLET_BLOCKED,
        RECOVERY_COMPLETED,
        ORPHAN_KEY
    }
}
