package com.memetrade.identity.api;

import java.util.Objects;
import java.util.UUID;

/** Authenticated person (C10): the only source of tenant and person for every module. */
public record Principal(UUID tenantId, UUID userId, Role role) {

    public Principal {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
    }
}
