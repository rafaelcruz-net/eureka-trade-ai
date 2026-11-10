package com.memetrade.shared.web;

import org.springframework.core.Ordered;

/** Single place that fixes the servlet filter order of the API process. */
public final class FilterOrder {

    public static final int CORRELATION = Ordered.HIGHEST_PRECEDENCE;
    public static final int SECURITY_HEADERS = Ordered.HIGHEST_PRECEDENCE + 1;
    public static final int CORS = Ordered.HIGHEST_PRECEDENCE + 2;

    /**
     * u1-legal-documents (NFR6.3): per-IP limit of the public legal documents, after the security headers and CORS
     * (a 429 carries them) and before authentication (the routes need no session).
     */
    public static final int LEGAL_RATE_LIMIT = Ordered.HIGHEST_PRECEDENCE + 3;

    public static final int AUTHENTICATION = Ordered.HIGHEST_PRECEDENCE + 10;
    /** U2: only an administrator passes {@code /api/v1/admin/**}, right after authentication (C8). */
    public static final int ADMIN_ROUTE = Ordered.HIGHEST_PRECEDENCE + 15;

    /**
     * u3-reacceptance (BR3.1, L3): a guest without an acceptance of the effective current terms only reaches the
     * allowed prefixes; after authentication and the role rule (an administrator token never gets here on a guest
     * route) and before the per-person limits, the mode guard and the idempotency key.
     */
    public static final int TERMS_REACCEPTANCE = Ordered.HIGHEST_PRECEDENCE + 16;

    /** U4: per person and route limit of the scanner routes, after authentication and the role rule (NFR6.3). */
    public static final int RATE_LIMIT = Ordered.HIGHEST_PRECEDENCE + 17;

    public static final int MODE_GUARD = Ordered.HIGHEST_PRECEDENCE + 20;
    public static final int IDEMPOTENCY_KEY = Ordered.HIGHEST_PRECEDENCE + 30;

    private FilterOrder() {}
}
