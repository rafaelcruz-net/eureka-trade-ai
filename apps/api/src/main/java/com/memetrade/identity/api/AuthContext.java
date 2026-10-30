package com.memetrade.identity.api;

import java.util.Optional;

/**
 * Authentication context (contract C10): the ONLY way any module obtains tenant and person. No module reads a
 * tenant from the request body, query or headers; a client-sent {@code tenantId} is ignored (BR2.1).
 *
 * <p>The principal is built per request by the identity module after checking that the session, the user and the
 * tenant are still active (BR1.14). Background jobs (from U5) receive a system principal carrying the tenant of the
 * resource and must validate that it matches.
 */
public interface AuthContext {

    /**
     * @return the authenticated principal of the current request
     * @throws UnauthenticatedException when there is no authenticated session
     */
    Principal current();

    /** The principal when the request is authenticated; empty otherwise (for auditing anonymous refusals). */
    Optional<Principal> optional();
}
