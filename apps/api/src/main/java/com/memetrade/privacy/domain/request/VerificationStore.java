package com.memetrade.privacy.domain.request;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence of the verifications ({@code data_subject_verification}); the signature is never a parameter here. */
public interface VerificationStore {

    void insert(IdentityVerification verification);

    Optional<IdentityVerification> find(UUID requestId);

    /**
     * BR4.12, NFR2.3: CONFIRMED only while PENDING and not expired at {@code now}.
     *
     * @return false when another confirmation or the expiration got there first
     */
    boolean confirm(UUID requestId, Instant now);

    /**
     * BR4.12: EXPIRED only while PENDING (the manual refusal of a waiting request).
     *
     * @return false when it was not PENDING any more
     */
    boolean expire(UUID requestId);
}
