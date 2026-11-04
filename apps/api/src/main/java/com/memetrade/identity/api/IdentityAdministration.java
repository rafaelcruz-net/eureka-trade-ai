package com.memetrade.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Administration of invitations and identity counts (contract C17), provided by U3 to the administrative routes
 * ({@code /api/v1/admin/...}, C8) and to U8. Every operation receives the authenticated administrator and refuses any
 * other role itself ({@code 403 FORBIDDEN_ROLE}), besides the administrative route rule (defence in depth, BR1.5).
 * Never used by a guest route (ArchUnit rule).
 */
public interface IdentityAdministration {

    /**
     * Issues a single-use PENDING invitation for an ACTIVE tenant with a validity of 1 hour to 30 days (BR1.1). Must
     * run inside the caller's transaction, together with the idempotency record (NFR6.3): the invitation, the
     * {@code INVITE_ISSUED} event and the stored response commit together.
     *
     * @throws com.memetrade.shared.error.ApiException {@code 422 VALIDATION_FAILED} on {@code tenantId} or
     *     {@code expiresAt}
     */
    IssuedInvite issue(UUID tenantId, Instant expiresAt, Principal admin);

    /**
     * Revokes a PENDING invitation (BR1.4); a REVOKED one is returned as it is, without a new event. Must run inside
     * the caller's transaction.
     *
     * @throws com.memetrade.shared.error.ApiException {@code 404 NOT_FOUND} or {@code 409 INVITE_NOT_REVOCABLE}
     */
    InviteView revoke(UUID inviteId, Principal admin);

    /** One page by {@code createdAt} descending, with the state derived at one instant for the whole page (BR1.3). */
    InvitePage list(Optional<String> cursor, int limit, Principal admin);

    /** Guests and invitations per derived state, numbers only (BR1.6). */
    IdentityCounts counts(Principal admin);

    /** The ACTIVE tenants, for the invitation form of U8 (Q1). */
    List<TenantOption> activeTenants(Principal admin);
}
