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
    /** U5 (BR2.1, AC4.4.3): a bot transition outside the table of the annex; nothing changes. */
    INVALID_TRANSITION(409, "conflict", "Invalid bot transition"),
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
    BOT_VALIDATION_FAILED(422, "validation", "Validation failed", "VALIDATION_FAILED");

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
