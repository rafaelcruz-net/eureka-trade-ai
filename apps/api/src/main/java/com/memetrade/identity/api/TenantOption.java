package com.memetrade.identity.api;

import java.util.UUID;

/** An ACTIVE tenant the administrator can choose for an invitation (C8/C17 addition, Q1, BR1.7). */
public record TenantOption(UUID tenantId, String name) {}
