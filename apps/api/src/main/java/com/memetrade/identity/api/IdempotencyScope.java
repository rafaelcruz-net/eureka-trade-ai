package com.memetrade.identity.api;

import com.memetrade.shared.wallet.WalletAddress;
import java.util.Objects;

/** Scope of an idempotency key: wallet + operation before login; tenant + person + operation after login. */
public record IdempotencyScope(String value) {

    public IdempotencyScope {
        Objects.requireNonNull(value, "value");
    }

    public static IdempotencyScope preLogin(WalletAddress wallet, IdempotentOperation operation) {
        return new IdempotencyScope("wallet:" + wallet.full() + ":" + operation.operationName());
    }

    public static IdempotencyScope authenticated(Principal principal, IdempotentOperation operation) {
        return new IdempotencyScope(
                "tenant:" + principal.tenantId() + ":user:" + principal.userId() + ":" + operation.operationName());
    }
}
