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
    RATE_LIMITED(429, "rate-limit", "Too many requests"),
    INTERNAL_ERROR(500, "internal", "Internal error"),
    SERVICE_UNAVAILABLE(503, "unavailable", "Service unavailable");

    private static final String TYPE_BASE = "https://memetrade.app/problems/";

    private final int status;
    private final String category;
    private final String title;

    ErrorCode(int status, String category, String title) {
        this.status = status;
        this.category = category;
        this.title = title;
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }

    public URI type() {
        return URI.create(TYPE_BASE + category);
    }
}
