package com.memetrade.shared.web;

import org.springframework.core.Ordered;

/** Single place that fixes the servlet filter order of the API process. */
public final class FilterOrder {

    public static final int CORRELATION = Ordered.HIGHEST_PRECEDENCE;
    public static final int SECURITY_HEADERS = Ordered.HIGHEST_PRECEDENCE + 1;
    public static final int CORS = Ordered.HIGHEST_PRECEDENCE + 2;
    public static final int AUTHENTICATION = Ordered.HIGHEST_PRECEDENCE + 10;
    /** U2: only an administrator passes {@code /api/v1/admin/**}, right after authentication (C8). */
    public static final int ADMIN_ROUTE = Ordered.HIGHEST_PRECEDENCE + 15;

    public static final int MODE_GUARD = Ordered.HIGHEST_PRECEDENCE + 20;
    public static final int IDEMPOTENCY_KEY = Ordered.HIGHEST_PRECEDENCE + 30;

    private FilterOrder() {}
}
