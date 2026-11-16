package com.memetrade.botwallet.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 261113-trading-real-bot U5 (BR5.1): the bot wallets the view of the administrator goes through. */
public interface AdminViewStore {

    /** The bot wallets, oldest first, after the cursor (created at, id), at most {@code limit}. */
    List<WalletRow> wallets(Optional<Instant> afterCreatedAt, Optional<UUID> afterId, int limit);

    /** The newest bot wallet of the person (ACTIVE before CLOSED). */
    Optional<WalletRow> walletOf(UUID userId);

    /** One bot wallet with its tenant, its person and its two addresses (either may be gone after an anonymization). */
    record WalletRow(
            UUID tenantId,
            UUID userId,
            UUID botWalletId,
            String status,
            String publicAddress,
            String mainWalletAddress,
            Instant createdAt) {}
}
