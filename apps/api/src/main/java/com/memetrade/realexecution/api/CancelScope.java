package com.memetrade.realexecution.api;

import java.util.Objects;
import java.util.UUID;

/** M6 {@code cancelUnsent}: the orders of one person, or of everybody (the kill switch of the U5). */
public sealed interface CancelScope permits CancelScope.User, CancelScope.All {

    static CancelScope user(UUID tenantId, UUID userId) {
        return new User(tenantId, userId);
    }

    static CancelScope all() {
        return new All();
    }

    record User(UUID tenantId, UUID userId) implements CancelScope {
        public User {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(userId, "userId");
        }
    }

    record All() implements CancelScope {}
}
