package com.memetrade.identity.domain.terms;

import java.util.UUID;

/**
 * u3-reacceptance (BR3.1, NFR3.2): read port of the reacceptance lock. One indexed {@code EXISTS} by the person, the
 * tenant of the authentication and the version, through the unique constraint {@code (user_id, terms_version)}. Never
 * cached (NFR3.4): every instance sees an acceptance as soon as it is committed.
 */
public interface TermsAcceptanceLookup {

    /**
     * @return true when the person has an acceptance of {@code version} in its tenant
     * @throws org.springframework.dao.DataAccessException when the database cannot answer (the lock answers 503)
     */
    boolean hasAccepted(UUID userId, UUID tenantId, String version);
}
