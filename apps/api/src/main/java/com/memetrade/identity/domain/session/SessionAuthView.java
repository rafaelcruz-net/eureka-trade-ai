package com.memetrade.identity.domain.session;

import com.memetrade.identity.api.Role;
import java.time.Instant;
import java.util.UUID;

/** What every authenticated request checks, read by primary key in one query (BR1.14). */
public record SessionAuthView(
        UUID sessionId,
        UUID userId,
        UUID tenantId,
        Role role,
        SessionState state,
        Instant absoluteExpiresAt,
        boolean userActive,
        boolean tenantActive) {}
