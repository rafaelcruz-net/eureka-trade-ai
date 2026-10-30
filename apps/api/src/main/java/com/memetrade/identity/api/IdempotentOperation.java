package com.memetrade.identity.api;

/**
 * State-changing operations that take an {@code Idempotency-Key}, with how their response is kept (BR1.10,
 * reliability-design.md Q6).
 */
public enum IdempotentOperation {
    /** Login challenge: kept only until the issued challenge expires; after that the same key issues a new one. */
    AUTH_CHALLENGE("auth.challenge", Retention.UNTIL_RESULT_EXPIRES),
    /** Login verification: never stored; the single-use nonce already guarantees a single session. */
    AUTH_VERIFY("auth.verify", Retention.NOT_STORED),
    /** Logout and every other state-changing route: default retention (24 hours). */
    AUTH_LOGOUT("auth.logout", Retention.DEFAULT);

    public enum Retention {
        DEFAULT,
        UNTIL_RESULT_EXPIRES,
        NOT_STORED
    }

    private final String operationName;
    private final Retention retention;

    IdempotentOperation(String operationName, Retention retention) {
        this.operationName = operationName;
        this.retention = retention;
    }

    public String operationName() {
        return operationName;
    }

    public Retention retention() {
        return retention;
    }
}
