package com.memetrade.bots.domain;

import com.memetrade.identity.api.Principal;
import java.util.Objects;
import java.util.UUID;

/**
 * Tenant and person every query of a bot filters by (NFR5.1, security-design.md). Built only from the authentication
 * context or, in the worker jobs, from the bot row itself (BR2.10); never from a request body.
 */
public record TenantScope(UUID tenantId, UUID ownerUserId) {

    public TenantScope {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(ownerUserId, "ownerUserId");
    }

    public static TenantScope of(Principal principal) {
        return new TenantScope(principal.tenantId(), principal.userId());
    }
}
