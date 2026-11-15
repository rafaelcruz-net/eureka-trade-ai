package com.memetrade.botwallet.api;

import java.util.Objects;
import java.util.UUID;

/** M1 (261113-trading-real-bot U3): the identity of an ACTIVE bot wallet, for the jobs of the worker. */
public record RealWalletRef(UUID tenantId, UUID userId, UUID botWalletId) {

    public RealWalletRef {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(botWalletId, "botWalletId");
    }
}
