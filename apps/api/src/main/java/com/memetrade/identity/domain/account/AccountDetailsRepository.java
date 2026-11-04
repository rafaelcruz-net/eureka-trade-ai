package com.memetrade.identity.domain.account;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Read port of the Account screen (F8, NFR3.2): person, wallet, current session and acceptance in ONE query, always
 * filtered by the person AND the tenant of the authenticated session (NFR5.1).
 */
public interface AccountDetailsRepository {

    Optional<AccountDetails> find(UUID userId, UUID tenantId, UUID sessionId);

    /**
     * What the screen needs; {@code termsVersion} and {@code acceptedAt} are {@code null} only for accounts created
     * without an invitation (the seeded smoke account of the test profile).
     */
    record AccountDetails(
            String walletAddress,
            String provider,
            Instant sessionAbsoluteExpiresAt,
            String termsVersion,
            Instant acceptedAt) {

        @Override
        public String toString() {
            // The full address never reaches a log line (NFR7.1).
            return "AccountDetails[provider=" + provider + "]";
        }
    }
}
