package com.memetrade.identity.domain.login;

import java.time.Instant;
import java.util.UUID;

/** Write port of the administrator account (entities.md AdminAccount): a user with role ADMIN and its wallet. */
public interface AdminAccounts {

    /**
     * Creates the ADMIN user in {@code tenantId} and links {@code walletAddress}, inside the verification transaction.
     *
     * @throws com.memetrade.identity.domain.account.WalletAlreadyLinkedException when a concurrent first entry of the
     *     same wallet won (the caller retries once and finds the account)
     */
    void createAdmin(UUID userId, UUID tenantId, String walletAddress, String provider, Instant now);
}
