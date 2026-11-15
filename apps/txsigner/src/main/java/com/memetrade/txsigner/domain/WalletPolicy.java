package com.memetrade.txsigner.domain;

import com.memetrade.txsigner.policy.OperationMode;
import java.time.Instant;
import java.util.UUID;

/**
 * Own policy of the TxSigner for one bot wallet (entities.md WalletPolicy), derived only from consents it checked
 * itself. The main wallet is fixed at creation and never changes. {@code blocked} is set by the recovery when the key
 * tags disagree with the policy; {@code restored} marks a policy rebuilt from the key tags (cap 0, SIGNED).
 */
public record WalletPolicy(
        String botWalletAddress,
        String kmsKeyRef,
        String mainWalletAddress,
        long authorizedCapLamports,
        OperationMode authorizedMode,
        boolean closed,
        boolean blocked,
        boolean restored,
        UUID creationRequestId,
        long maxWithdrawalFeeLamports,
        Instant createdAt,
        Instant updatedAt) {

    public WalletPolicy withLimits(long capLamports, OperationMode mode, Instant now) {
        return new WalletPolicy(
                botWalletAddress,
                kmsKeyRef,
                mainWalletAddress,
                capLamports,
                mode,
                closed,
                blocked,
                restored,
                creationRequestId,
                maxWithdrawalFeeLamports,
                createdAt,
                now);
    }

    public WalletPolicy closedAt(Instant now) {
        return new WalletPolicy(
                botWalletAddress,
                kmsKeyRef,
                mainWalletAddress,
                authorizedCapLamports,
                authorizedMode,
                true,
                blocked,
                restored,
                creationRequestId,
                maxWithdrawalFeeLamports,
                createdAt,
                now);
    }

    public WalletPolicy blockedAt(Instant now) {
        return new WalletPolicy(
                botWalletAddress,
                kmsKeyRef,
                mainWalletAddress,
                authorizedCapLamports,
                authorizedMode,
                closed,
                true,
                restored,
                creationRequestId,
                maxWithdrawalFeeLamports,
                createdAt,
                now);
    }
}
