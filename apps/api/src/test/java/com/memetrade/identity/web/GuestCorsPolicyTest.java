package com.memetrade.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

@Tag("u1")
class GuestCorsPolicyTest {

    private static final String GUEST = "http://localhost:3000";

    private final CorsFilter filter = new CorsFilter(GuestCorsPolicy.source(URI.create(GUEST + "/")));

    @Test
    void aPreflightFromTheGuestOriginIsAllowed() throws Exception {
        MockHttpServletResponse response = preflight(GUEST, "/api/v1/scanner/tokens", "GET");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(GUEST);
    }

    @Test
    void anOriginNotListedIsRefused() throws Exception {
        MockHttpServletResponse response = preflight("https://evil.example", "/api/v1/auth/verify", "POST");

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void credentialsAreAllowedOnlyOnVerifyRefreshAndLogout() throws Exception {
        for (String route : GuestCorsPolicy.CREDENTIAL_ROUTES) {
            assertThat(preflight(GUEST, route, "POST").getHeader("Access-Control-Allow-Credentials"))
                    .as(route)
                    .isEqualTo("true");
        }
        assertThat(preflight(GUEST, "/api/v1/auth/challenge", "POST").getHeader("Access-Control-Allow-Credentials"))
                .isNull();
        assertThat(preflight(GUEST, "/api/v1/scanner/tokens", "GET").getHeader("Access-Control-Allow-Credentials"))
                .isNull();
    }

    /** Regression: the U5 wizard saves the draft with a cross-origin PUT, which the guest policy used to refuse. */
    @Test
    void thePutOfTheBotDraftIsAllowedButNotOnTheCookieRoutes() throws Exception {
        MockHttpServletResponse draft = preflight(GUEST, "/api/v1/bots/" + UUID.randomUUID() + "/draft", "PUT");

        assertThat(draft.getStatus()).isEqualTo(200);
        assertThat(draft.getHeader("Access-Control-Allow-Methods")).contains("PUT");
        assertThat(draft.getHeader("Access-Control-Allow-Credentials")).isNull();
        for (String route : GuestCorsPolicy.CREDENTIAL_ROUTES) {
            assertThat(preflight(GUEST, route, "PUT").getStatus()).as(route).isEqualTo(403);
        }
        assertThat(preflight(GUEST, "/api/v1/scanner/tokens", "DELETE").getStatus())
                .isEqualTo(403);
    }

    @Test
    void theIdempotencyAndCorrelationHeadersMayBeSent() throws Exception {
        MockHttpServletRequest request = preflightRequest(GUEST, "/api/v1/auth/challenge", "POST");
        request.addHeader("Access-Control-Request-Headers", "idempotency-key, x-correlation-id, content-type");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Headers")).containsIgnoringCase("idempotency-key");
    }

    /**
     * U9 regression (BR5.3): the dashboard reopens the live channel with Last-Event-ID and X-Correlation-Id besides the
     * token; without them in the list the browser refuses every reopening after the first event.
     */
    @Test
    void theLiveChannelMayBeReopenedWithLastEventId() throws Exception {
        MockHttpServletRequest request = preflightRequest(GUEST, "/api/v1/stream/dashboard", "GET");
        request.addHeader("Access-Control-Request-Headers", "authorization, last-event-id, x-correlation-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Headers")).containsIgnoringCase("last-event-id");
        assertThat(response.getHeader("Access-Control-Expose-Headers")).contains("Retry-After");
        assertThat(preflight(GUEST, "/api/v1/stream/dashboard", "GET").getHeader("Access-Control-Allow-Credentials"))
                .isNull();
    }

    /**
     * u1-legal-documents (BR1.7, NFR6.2): the legal documents are registered before /api/v1/** and only read; in the
     * combined source they accept the administrator origin too, never with credentials.
     */
    @Test
    void theLegalDocumentsAreOnlyReadFromBothAppOriginsWithoutCredentials() throws Exception {
        String admin = "http://localhost:3001";
        CorsFilter combined = new CorsFilter(AdminCorsPolicy.combined(URI.create(admin), URI.create(GUEST + "/")));

        for (String origin : java.util.List.of(GUEST, admin)) {
            MockHttpServletResponse read = new MockHttpServletResponse();
            combined.doFilter(preflightRequest(origin, "/api/v1/legal/current", "GET"), read, new MockFilterChain());
            assertThat(read.getStatus()).as(origin).isEqualTo(200);
            assertThat(read.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
            assertThat(read.getHeader("Access-Control-Allow-Methods")).isEqualTo("GET,HEAD");
            assertThat(read.getHeader("Access-Control-Allow-Credentials")).isNull();
            MockHttpServletResponse write = new MockHttpServletResponse();
            combined.doFilter(preflightRequest(origin, "/api/v1/legal/current", "POST"), write, new MockFilterChain());
            assertThat(write.getStatus()).as(origin).isEqualTo(403);
        }
        assertThat(preflight(GUEST, "/api/v1/legal/versions", "GET").getStatus())
                .isEqualTo(200);
        assertThat(preflight(admin, "/api/v1/legal/versions", "GET").getStatus())
                .as("the guest-only source never admits the administrator origin")
                .isEqualTo(403);
        assertThat(preflight(admin, "/api/v1/scanner/tokens", "GET").getStatus())
                .isEqualTo(403);
    }

    /**
     * 261113-trading-real-bot U6, Step 3, written before the code (rules.md "Mudança de contrato" item 4, U6-NFR1.7):
     * the preferences of the REAL are a {@code PATCH}; it is accepted on the routes with token only.
     */
    @Test
    void thePatchOfThePreferencesIsAllowedOnTheTokenRoutes() throws Exception {
        MockHttpServletResponse preferences = preflight(GUEST, "/api/v1/real/bot-wallet/preferences", "PATCH");

        assertThat(preferences.getStatus()).isEqualTo(200);
        assertThat(preferences.getHeader("Access-Control-Allow-Methods")).contains("PATCH");
        assertThat(preferences.getHeader("Access-Control-Allow-Credentials")).isNull();
    }

    /** U6-NFR1.7: the cookie branch (verify, refresh, logout) still refuses {@code PATCH}. */
    @Test
    void thePatchIsRefusedOnTheCookieRoutes() throws Exception {
        for (String route : GuestCorsPolicy.CREDENTIAL_ROUTES) {
            MockHttpServletResponse response = preflight(GUEST, route, "PATCH");
            assertThat(response.getStatus()).as(route).isEqualTo(403);
            assertThat(response.getHeader("Access-Control-Allow-Origin"))
                    .as(route)
                    .isNull();
        }
    }

    /**
     * U6-NFR1.7: the administrative policy does not take {@code PATCH}, and the guest origin is never admitted there.
     * U6 deviation (code-summary.md): the administrator app sends the {@code DELETE} routes of the R4 of the U5 (revoke,
     * switch the kill switch REAL off, unblock a token) cross origin, so the administrative policy lists DELETE.
     */
    @Test
    void theAdministrativePolicyTakesNoPatchButTakesTheDeleteOfTheR4() throws Exception {
        String admin = "http://localhost:3001";
        CorsFilter combined = new CorsFilter(AdminCorsPolicy.combined(URI.create(admin), URI.create(GUEST + "/")));

        MockHttpServletResponse patch = new MockHttpServletResponse();
        combined.doFilter(
                preflightRequest(admin, "/api/v1/admin/real/kill-switch", "PATCH"), patch, new MockFilterChain());
        MockHttpServletResponse delete = new MockHttpServletResponse();
        combined.doFilter(
                preflightRequest(admin, "/api/v1/admin/real/kill-switch", "DELETE"), delete, new MockFilterChain());
        MockHttpServletResponse guestOnAdmin = new MockHttpServletResponse();
        combined.doFilter(
                preflightRequest(GUEST, "/api/v1/admin/real/kill-switch", "DELETE"),
                guestOnAdmin,
                new MockFilterChain());
        MockHttpServletResponse cookieDelete = new MockHttpServletResponse();
        combined.doFilter(
                preflightRequest(admin, "/api/v1/admin/auth/logout", "DELETE"), cookieDelete, new MockFilterChain());

        assertThat(patch.getStatus()).isEqualTo(403);
        assertThat(delete.getStatus()).isEqualTo(200);
        assertThat(delete.getHeader("Access-Control-Allow-Methods")).contains("DELETE");
        assertThat(guestOnAdmin.getStatus()).isEqualTo(403);
        assertThat(cookieDelete.getStatus()).isEqualTo(403);
    }

    private MockHttpServletResponse preflight(String origin, String path, String method) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(preflightRequest(origin, path, method), response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest preflightRequest(String origin, String path, String method) {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", path);
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", method);
        return request;
    }
}
