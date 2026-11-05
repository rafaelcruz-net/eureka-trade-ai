package com.memetrade.bots.domain;

import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import java.util.Objects;
import java.util.UUID;

/** A bot with the scope read from its own row, as the worker jobs hand it around (BR2.10). */
public record BotRef(UUID botId, TenantScope scope) {

    public BotRef {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(scope, "scope");
    }

    public Principal owner() {
        return new Principal(scope.tenantId(), scope.ownerUserId(), Role.BETA_USER);
    }
}
