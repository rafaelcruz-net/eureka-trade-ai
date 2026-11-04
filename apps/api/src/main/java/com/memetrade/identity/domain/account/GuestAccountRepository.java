package com.memetrade.identity.domain.account;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes of the account creation with an invitation (BR3.3, BR3.9): the person, the wallet (public address and
 * provider only) and the acceptance of the terms. Runs inside the verification transaction.
 */
public interface GuestAccountRepository {

    /**
     * Inserts the BETA_USER person (ACTIVE, in the tenant of the invitation) and its wallet.
     *
     * @throws WalletAlreadyLinkedException when the unique constraint {@code uq_wallet_address} is violated by a
     *     concurrent creation with the same wallet (BR3.7); any other violation is a technical failure
     */
    void createGuest(UUID userId, UUID tenantId, String walletAddress, String provider, Instant now);

    /** Inserts the acceptance of the terms and risk notice; never updated nor deleted (insert-only table). */
    void recordAcceptance(TermsAcceptance acceptance);

    /** Acceptance of the terms recorded at the account creation (entities.md TermsAcceptance). */
    record TermsAcceptance(
            UUID acceptanceId, UUID userId, UUID tenantId, String termsVersion, Instant acceptedAt, UUID inviteId) {}
}
