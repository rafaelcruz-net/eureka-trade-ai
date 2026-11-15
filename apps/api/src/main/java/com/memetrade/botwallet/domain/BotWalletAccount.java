package com.memetrade.botwallet.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The bot wallet of a person (entities.md BotWalletAccount). The key reference is NOT here: it stays in the TxSigner
 * (NFR1.7, decision of the Code Generation of the U1). The main wallet is fixed at creation and never changes.
 */
public record BotWalletAccount(
        UUID botWalletId,
        UUID tenantId,
        UUID userId,
        String publicAddress,
        String mainWalletAddress,
        Network network,
        String status,
        String depositCursor,
        boolean policyResetRequired,
        Instant createdAt) {

    public static BotWalletAccount activate(
            UUID tenantId, UUID userId, String publicAddress, String mainWalletAddress, Network network, Instant now) {
        return new BotWalletAccount(
                UUID.randomUUID(),
                tenantId,
                userId,
                publicAddress,
                mainWalletAddress,
                network,
                "ACTIVE",
                null,
                false,
                now);
    }
}
