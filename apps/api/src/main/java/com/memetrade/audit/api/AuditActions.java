package com.memetrade.audit.api;

/** Audited actions of U1 (BR4.2) and U2. Later Units add their own names without changing the publisher. */
public final class AuditActions {

    /** U2 (BR2.5): every successful query of the trail by an administrator. */
    public static final String AUDIT_QUERIED = "AUDIT_QUERIED";

    /** U2 (BR3.3): every run of the retention purge, completed or stopped. */
    public static final String AUDIT_RETENTION_PURGED = "AUDIT_RETENTION_PURGED";

    /** U1: a login verified by the wallet signature opened a session. */
    public static final String LOGIN_SUCCEEDED = "LOGIN_SUCCEEDED";

    /** U1: a login verification was refused (expired or reused nonce, bad signature, unknown account). */
    public static final String LOGIN_REJECTED = "LOGIN_REJECTED";

    /** U1: a session was closed by its owner. */
    public static final String LOGOUT = "LOGOUT";

    /** U1/U2 (BR2.1): a request was refused with 403; one event per refusal, never aggregated (ADR-0003). */
    public static final String REQUEST_FORBIDDEN = "REQUEST_FORBIDDEN";

    /** U1: a request named a resource of another tenant. */
    public static final String TENANT_MISMATCH = "TENANT_MISMATCH";

    /** U1: a refresh token already used was presented again; the session family is revoked. */
    public static final String SESSION_REFRESH_REUSED = "SESSION_REFRESH_REUSED";

    /** U4 (BR4.2): an administrator activated a weight version of the risk indicator; details carry both versions. */
    public static final String RISK_WEIGHTS_ACTIVATED = "RISK_WEIGHTS_ACTIVATED";

    /** U3 (BR1.1): an administrator issued an invitation; target {@code invite:<id>}, never the code. */
    public static final String INVITE_ISSUED = "INVITE_ISSUED";

    /** U3 (BR1.4): an administrator revoked a pending invitation; a repeated revocation writes nothing. */
    public static final String INVITE_REVOKED = "INVITE_REVOKED";

    /** U3 (BR3.5): an account was created with an invitation; details carry the terms version and acceptance time. */
    public static final String ACCOUNT_CREATED = "ACCOUNT_CREATED";

    /** U7 (BR2.6): the initial virtual balance of a bot was recorded; target {@code bot:<botId>}. */
    public static final String PAPER_ACCOUNT_FUNDED = "PAPER_ACCOUNT_FUNDED";

    /** U7 (F2): a PAPER order was filled, totally or partially; target {@code order:<orderId>}. */
    public static final String PAPER_ORDER_FILLED = "PAPER_ORDER_FILLED";

    /** U7 (F2): a PAPER order was recorded without a fill; details carry the reason, never values. */
    public static final String PAPER_ORDER_NOT_FILLED = "PAPER_ORDER_NOT_FILLED";

    /** U7 (BR5.2): a position waits for a reliable price to be closed. */
    public static final String POSITION_CLOSE_PENDING = "POSITION_CLOSE_PENDING";

    /** U7 (BR5.3): the pending close was completed once by the job, actor {@code system}. */
    public static final String POSITION_CLOSE_COMPLETED = "POSITION_CLOSE_COMPLETED";

    /** U7 (BR4.2): a ledger entry was corrected by a compensating entry. */
    public static final String LEDGER_ENTRY_COMPENSATED = "LEDGER_ENTRY_COMPENSATED";

    /**
     * U6 (BR3.5): the barrier refused an order; actor {@code system}, target {@code bot:<botId>}, details rejectionId,
     * decisionId, reasons, pauseTriggers and limitsVersion. One per rejection; an approval publishes nothing.
     */
    public static final String ORDER_REJECTED = "ORDER_REJECTED";

    /** U5 (NFR9.4): a bot draft was created; target {@code bot:<botId>}. */
    public static final String BOT_CREATED = "BOT_CREATED";

    /** U5 (BR2.2): the bot became ACTIVE (and was funded on its first activation). */
    public static final String BOT_STARTED = "BOT_STARTED";

    /** U5 (BR2.3, BR3.4, BR7.1): a pause reason was added; details carry the reason (PERSON, DAILY_LOSS, ...). */
    public static final String BOT_PAUSED = "BOT_PAUSED";

    /** U5 (BR2.4, BR2.6): the bot returned to ACTIVE, by its owner or at UTC midnight (actor system). */
    public static final String BOT_RESUMED = "BOT_RESUMED";

    /** U5 (BR2.6): the UTC midnight removed the daily loss reason; actor system. */
    public static final String BOT_DAILY_LOSS_CLEARED = "BOT_DAILY_LOSS_CLEARED";

    /** U5 (BR5.1): a new limits version; details carry the version numbers, never amounts of other people. */
    public static final String BOT_LIMITS_CHANGED = "BOT_LIMITS_CHANGED";

    /** U5 (BR2.7, BR2.8): the bot is STOPPED (by its owner, or by the closing check with actor system). */
    public static final String BOT_STOPPED = "BOT_STOPPED";

    /** U5 (BR2.7): the stop left positions without a reliable price; the bot waits in CLOSING_PENDING. */
    public static final String BOT_CLOSING_PENDING = "BOT_CLOSING_PENDING";

    /** U5 (BR7.2): the kill switch was switched off; SYSTEM became ADMIN_RELEASED on this bot; actor system. */
    public static final String BOT_SYSTEM_PAUSE_LIFTED = "BOT_SYSTEM_PAUSE_LIFTED";

    private AuditActions() {}
}
