package com.memetrade.shared.error;

import java.net.URI;

/**
 * Machine-readable error codes of the API (contract-summary.md, "Códigos de erro de domínio"). Each code has a
 * stable problem {@code type} per category and its HTTP status. Domain errors are 4xx; technical errors 5xx/503.
 */
public enum ErrorCode {
    VALIDATION_FAILED(400, "validation", "Validation failed"),
    UNAUTHENTICATED(401, "authentication", "Authentication required"),
    LOGIN_CHALLENGE_REJECTED(401, "authentication", "Login challenge rejected"),
    FORBIDDEN_ROLE(403, "authorization", "Role not allowed"),
    REAL_MODE_UNAVAILABLE(403, "authorization", "REAL mode is unavailable"),
    NOT_FOUND(404, "not-found", "Resource not found"),
    METHOD_NOT_ALLOWED(405, "validation", "Method not allowed"),
    IDEMPOTENCY_CONFLICT(409, "conflict", "Idempotency key conflict"),
    /** U4 (BR4.2): an ACTIVE or RETIRED weight version never changes; a RETIRED one is never activated again. */
    WEIGHT_SET_NOT_EDITABLE(409, "conflict", "Weight set not editable"),
    RATE_LIMITED(429, "rate-limit", "Too many requests"),
    INTERNAL_ERROR(500, "internal", "Internal error"),
    SERVICE_UNAVAILABLE(503, "unavailable", "Service unavailable"),
    /** U4 (BR1.4, AC2.3.3): every market source FAILED; the problem lists the state of each source. */
    SOURCE_UNAVAILABLE(503, "unavailable", "Market sources unavailable"),
    /**
     * U3 (BR1.1): a well-formed request whose values break a business rule (the tenant or the validity of an
     * invitation) is {@code 422} with the machine code {@code VALIDATION_FAILED} and the list of fields (C8).
     */
    UNPROCESSABLE_FIELDS(422, "validation", "Validation failed", "VALIDATION_FAILED"),
    /** U3 (BR2.1, BR3.2): unknown or malformed invitation code; the same generic answer for both (NFR6.7). */
    INVITE_NOT_FOUND(404, "not-found", "Invitation not found"),
    /** U3 (BR3.2): the invitation is EXPIRED, REVOKED or USED, or its tenant is not ACTIVE; {@code reason} says which. */
    INVITE_INVALID(422, "invitation", "Invitation cannot be used"),
    /** U3 (BR3.1): the current version of the terms and risk notice was not accepted. */
    TERMS_NOT_ACCEPTED(422, "invitation", "Terms not accepted"),
    /** U3 (BR3.7): the wallet already has an account; it signs in through the normal entry. */
    WALLET_ALREADY_LINKED(409, "conflict", "Wallet already linked"),
    /** U3 (BR1.4): only a PENDING invitation can be revoked; {@code reason} is USED or EXPIRED. */
    INVITE_NOT_REVOCABLE(409, "conflict", "Invitation cannot be revoked"),
    /**
     * U5 (BR2.1, AC4.4.3): a bot transition outside the table of the annex; nothing changes. u4-data-requests (BR4.5,
     * contract-summary.md): reused by the data subject requests, so the title is generic and the {@code detail} says
     * which transition.
     */
    INVALID_TRANSITION(409, "conflict", "Invalid state transition"),
    /** U5 (BR2.2, AC4.3.2): the person already has 3 active bots. */
    ACTIVE_BOT_LIMIT(409, "conflict", "Active bot limit reached"),
    /** U5 (BR2.4, AC7.2.3): the kill switch is on; start and resume are refused. */
    PAUSED_BY_SYSTEM(409, "conflict", "Paused by the system"),
    /** U5 (BR2.4, AC4.7.5): resume after the daily loss with a limit not above the loss of the UTC day. */
    DAILY_LOSS_LIMIT_REACHED(409, "conflict", "Daily loss limit reached"),
    /** U5 (BR1.5, AC4.2.3): incoherent limits (order above token exposure or token exposure above total). */
    LIMIT_INCOHERENT(422, "validation", "Incoherent limits"),
    /** U5 (BR1.4, AC4.2.4): virtual balance above the cap of the platform. */
    VIRTUAL_BALANCE_ABOVE_CAP(422, "validation", "Virtual balance above the cap"),
    /** U5 (BR1.1 to BR1.7): a well-formed draft or limits body whose values break a rule; the fields say which. */
    BOT_VALIDATION_FAILED(422, "validation", "Validation failed", "VALIDATION_FAILED"),
    /**
     * u3-reacceptance (BR3.1, L3): a guest without an acceptance of the effective current version of the terms; every
     * guest route outside the allowed prefixes answers this, with {@code currentVersion} in the problem.
     */
    TERMS_REACCEPTANCE_REQUIRED(403, "terms", "Terms reacceptance required"),
    /** u3-reacceptance (BR3.6, L2): the accepted version is not the effective current one; nothing is recorded. */
    TERMS_VERSION_OUTDATED(409, "terms", "Terms version outdated"),
    /**
     * u4-data-requests (BR4.2, P1, P2): the person already has an open request (RECEIVED, AWAITING_VERIFICATION or
     * IN_PROGRESS) of the same type, from any origin.
     */
    DATA_REQUEST_ALREADY_OPEN(409, "data-request", "Data request already open"),
    /** u4-data-requests (BR4.5, BR4.12, P2): the request is DONE or REFUSED; nothing changes. */
    DATA_REQUEST_CLOSED(409, "data-request", "Data request closed"),
    /** u4-data-requests (BR4.9, P2): an EMAIL request needs a confirmed identity before the summary is released. */
    IDENTITY_NOT_VERIFIED(409, "data-request", "Identity not verified"),
    /** u4-data-requests (BR4.12, P2): the verification message is past its 24 hours. */
    VERIFICATION_EXPIRED(409, "data-request", "Verification expired"),
    /** u4-data-requests (BR4.12, P2): the verification was already confirmed; it is single-use. */
    VERIFICATION_ALREADY_USED(409, "data-request", "Verification already used"),
    /**
     * u4-data-requests (BR4.12, P2): the signature is not the signature of the wallet of the account over the message;
     * the request keeps waiting.
     */
    VERIFICATION_INVALID(422, "data-request", "Verification invalid"),
    /** u5-anonymization (BR5.1, P2): the body does not carry the exact confirmation {@code ANONIMIZAR}. */
    CONFIRMATION_REQUIRED(422, "data-request", "Confirmation required"),
    /** u5-anonymization (BR5.1, P2): only a DELETION request IN_PROGRESS is anonymized; nothing changes. */
    REQUEST_NOT_ELIGIBLE(409, "data-request", "Request not eligible"),
    /** u5-anonymization (BR5.1, P2): the request is not linked to an account; nothing changes. */
    NO_LINKED_ACCOUNT(409, "data-request", "No linked account"),
    /** u5-anonymization (BR5.1, NFR2.1): the linked account is an ADMIN account, never anonymized. */
    ADMIN_ACCOUNT_NOT_ANONYMIZABLE(409, "data-request", "Administrator account not anonymizable"),
    /**
     * u5-anonymization (NFR5.4): a row lock was not obtained within {@code lock_timeout}; nothing changed and the same
     * Idempotency-Key may be repeated ({@code Retry-After}).
     */
    LOCK_TIMEOUT(503, "unavailable", "Lock not obtained in time"),
    /** 261113-trading-real-bot (contract-summary.md "Códigos de erro novos"), U1 of the bot wallet. */
    REAL_DISABLED(403, "real", "REAL is disabled in this environment"),
    REAL_NOT_RELEASED(403, "real", "REAL is not released for this account"),
    BOT_WALLET_ALREADY_ACTIVE(409, "conflict", "Bot wallet already active"),
    BOT_WALLET_NOT_ACTIVE(409, "conflict", "Bot wallet not active"),
    REAL_PAUSED(409, "conflict", "REAL paused"),
    REAL_NOT_PAUSED(409, "conflict", "REAL not paused by the person"),
    ORDER_AMBIGUOUS(409, "conflict", "An ambiguous order or withdrawal is still open"),
    CONSENT_INVALID(422, "consent", "Consent invalid"),
    CONSENT_EXPIRED(422, "consent", "Consent expired"),
    CAP_ABOVE_MAXIMUM(422, "validation", "Cap above the maximum"),
    INSUFFICIENT_FREE_BALANCE(422, "balance", "Insufficient free balance"),
    WITHDRAWAL_DESTINATION_INVALID(422, "withdrawal", "Withdrawal destination invalid"),
    SIGNING_POLICY_REFUSED(422, "signing", "Signing policy refused"),
    // 261113-trading-real-bot U4 (contract-summary.md "Códigos de erro novos", R3).
    SIGNING_DEADLINE_EXPIRED(409, "signing", "Signing deadline expired"),
    SIGNED_MESSAGE_MISMATCH(422, "signing", "Signed message mismatch"),
    ORDER_ALREADY_IN_PROGRESS(409, "conflict", "A liquidation is already in progress"),
    UPSTREAM_UNAVAILABLE(503, "unavailable", "Upstream unavailable");

    private static final String TYPE_BASE = "https://memetrade.app/problems/";

    private final int status;
    private final String category;
    private final String title;
    private final String code;

    ErrorCode(int status, String category, String title) {
        this(status, category, title, null);
    }

    ErrorCode(int status, String category, String title, String code) {
        this.status = status;
        this.category = category;
        this.title = title;
        this.code = code;
    }

    public int status() {
        return status;
    }

    /** The machine-readable {@code code} of the problem: the constant name unless the constant names another one. */
    public String code() {
        return code == null ? name() : code;
    }

    public String title() {
        return title;
    }

    public URI type() {
        return URI.create(TYPE_BASE + category);
    }
}
