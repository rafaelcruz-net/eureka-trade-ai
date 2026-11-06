package com.memetrade.identity.web;

import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;

/**
 * Refresh cookie of the administrator app (BR1.3, security-design.md R-07): HttpOnly, Secure, SameSite=Strict and scoped
 * to the administrator refresh route only, with its own name, so it never reaches a guest route and the guest cookie
 * never reaches this one.
 */
public final class AdminRefreshCookie {

    public static final String NAME = "mt_admin_refresh";
    public static final String PATH = "/api/v1/admin/auth/refresh";

    private AdminRefreshCookie() {}

    public static String issue(String refreshToken, Instant expiresAt, Instant now) {
        long seconds = Math.max(0, Duration.between(now, expiresAt).toSeconds());
        return ResponseCookie.from(NAME, refreshToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(seconds)
                .build()
                .toString();
    }

    public static String clear() {
        return ResponseCookie.from(NAME, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(0)
                .build()
                .toString();
    }
}
