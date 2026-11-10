package com.memetrade.identity.domain.terms;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * u3-reacceptance (BR3.4, BR3.5, NFR3.5): write port of the acceptances without an invitation. The table is insert
 * only: there is no update and no delete here.
 */
public interface TermsAcceptanceStore {

    /**
     * {@code INSERT ... ON CONFLICT (user_id, terms_version) DO NOTHING}.
     *
     * @return true when this call inserted the row; false when the person already had an acceptance of the version
     */
    boolean insertIfAbsent(NewAcceptance acceptance);

    /** When (UTC) the person accepted {@code version} in its tenant, if it did. */
    Optional<Instant> acceptedAt(UUID userId, UUID tenantId, String version);

    /** One acceptance to record; tenant and person always come from the authenticated principal. */
    record NewAcceptance(
            UUID acceptanceId,
            UUID userId,
            UUID tenantId,
            String version,
            Instant acceptedAt,
            TermsAcceptanceSource source) {

        public NewAcceptance {
            Objects.requireNonNull(acceptanceId, "acceptanceId");
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            Objects.requireNonNull(source, "source");
        }
    }
}
