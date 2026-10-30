package com.memetrade.identity.web;

import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;

/**
 * Refresh-token cookie of the guest app (contract-summary.md "Renovação"): HttpOnly, Secure, SameSite=Strict and
 * scoped to the refresh route only.
 */
public final class RefreshCookie {

    public static final String NAME = "mt_refresh";
    public static final String PATH = "/api/v1/auth/refresh";

    private RefreshCookie() {}

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
