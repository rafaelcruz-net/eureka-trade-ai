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
    AUTH_LOGOUT("auth.logout", Retention.DEFAULT),
    /**
     * U3 (NFR6.3, BR1.8): issuance of an invitation. The stored response is the invitation WITHOUT its code; the code
     * reaches the administrator only in the first, non-replayed answer.
     */
    ADMIN_INVITE_ISSUE("admin.invite.issue", Retention.DEFAULT),
    /** U3 (BR1.4, NFR4.5): revocation of an invitation. */
    ADMIN_INVITE_REVOKE("admin.invite.revoke", Retention.DEFAULT),
    /** U7 (BR2.6): initial virtual balance of a bot, called by U5 inside the activation transaction. */
    PAPER_FUND("paper.fund", Retention.DEFAULT),
    /** U7 (BR5.1): closing of the positions of a bot, called by U5 inside the closing transaction. */
    PAPER_CLOSE_POSITIONS("paper.close-positions", Retention.DEFAULT),
    /**
     * U7 (BR8.3): "check now" of the reconciliation, {@code POST /api/v1/reconciliation/run}. Added beyond the plan
     * because every POST takes an Idempotency-Key and project.md asks for the common mechanism in every new Unit.
     */
    RECONCILIATION_RUN("pnl.reconciliation.run", Retention.DEFAULT);

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
