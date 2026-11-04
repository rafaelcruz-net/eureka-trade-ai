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
    INVITE_NOT_REVOCABLE(409, "conflict", "Invitation cannot be revoked");

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
