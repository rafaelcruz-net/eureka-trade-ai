package com.memetrade.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u1")
class RefreshCookieTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

    @Test
    void theRefreshCookieIsHttpOnlySecureStrictAndScopedToTheRefreshRoute() {
        String cookie = RefreshCookie.issue("token-value", NOW.plus(Duration.ofHours(8)), NOW);

        assertThat(cookie)
                .startsWith("mt_refresh=token-value")
                .contains("Path=/api/v1/auth/refresh")
                .contains("Max-Age=28800")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict");
    }

    @Test
    void clearingExpiresTheCookieOnTheSamePath() {
        assertThat(RefreshCookie.clear())
                .startsWith("mt_refresh=;")
                .contains("Max-Age=0")
                .contains("Path=/api/v1/auth/refresh");
    }
}
