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

    private AuditActions() {}
}
