package com.memetrade.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import com.memetrade.testsupport.AbstractU8IT;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Passo 11 of U8 (security-design.md "CORS do app do administrador", R-04 and R-08 of the NFR Design): the preflight of
 * the PUT of the kill switch from the administrator app passes; any other origin is refused on the administrative
 * routes; credentials only on verify, refresh and logout of the administrator entry; the guest policy is unchanged and
 * refuses the administrator origin.
 */
class AdminCorsIT extends AbstractU8IT {

    private static final String ADMIN_ORIGIN = "http://localhost:3001";
    private static final String GUEST_ORIGIN = "http://localhost:3000";

    @Test
    void thePreflightOfThePutOfTheKillSwitchPassesFromTheAdministratorApp() throws Exception {
        MockHttpServletResponse preflight = preflight("/api/v1/admin/kill-switch", ADMIN_ORIGIN, "PUT");

        assertThat(preflight.getStatus()).isEqualTo(200);
        assertThat(preflight.getHeader("Access-Control-Allow-Origin")).isEqualTo(ADMIN_ORIGIN);
        assertThat(preflight.getHeader("Access-Control-Allow-Methods")).contains("PUT");
        assertThat(preflight.getHeader("Access-Control-Allow-Headers")).containsIgnoringCase("Idempotency-Key");
        assertThat(preflight.getHeader("Access-Control-Allow-Credentials")).isNull();
    }

    @Test
    void anyOtherOriginIsRefusedOnTheAdministrativeRoutes() throws Exception {
        assertThat(preflight("/api/v1/admin/kill-switch", GUEST_ORIGIN, "PUT").getStatus())
                .isEqualTo(403);
        assertThat(preflight("/api/v1/admin/system-status", "http://evil.example", "GET")
                        .getStatus())
                .isEqualTo(403);
        assertThat(preflight("/api/v1/admin/auth/verify", GUEST_ORIGIN, "POST").getStatus())
                .isEqualTo(403);
    }

    /** R-08: verify sets the cookie, so it needs credentials too; refresh and logout use the cookie. */
    @Test
    void credentialsOnlyOnVerifyRefreshAndLogout() throws Exception {
        for (String route :
                new String[] {"/api/v1/admin/auth/verify", "/api/v1/admin/auth/refresh", "/api/v1/admin/auth/logout"}) {
            MockHttpServletResponse preflight = preflight(route, ADMIN_ORIGIN, "POST");
            assertThat(preflight.getStatus()).as(route).isEqualTo(200);
            assertThat(preflight.getHeader("Access-Control-Allow-Credentials"))
                    .as(route)
                    .isEqualTo("true");
        }
        for (String route : new String[] {
            "/api/v1/admin/auth/challenge", "/api/v1/admin/invites", "/api/v1/admin/feedback", "/api/v1/admin/tenants"
        }) {
            MockHttpServletResponse preflight = preflight(route, ADMIN_ORIGIN, "POST");
            assertThat(preflight.getStatus()).as(route).isEqualTo(200);
            assertThat(preflight.getHeader("Access-Control-Allow-Credentials"))
                    .as(route)
                    .isNull();
        }
    }

    /** The guest policy does not change: guest origin on guest routes, never the administrator origin. */
    @Test
    void theGuestPolicyIsUnchangedAndRefusesTheAdministratorOrigin() throws Exception {
        MockHttpServletResponse guest = preflight("/api/v1/feedback", GUEST_ORIGIN, "POST");
        assertThat(guest.getStatus()).isEqualTo(200);
        assertThat(guest.getHeader("Access-Control-Allow-Origin")).isEqualTo(GUEST_ORIGIN);
        assertThat(preflight("/api/v1/auth/verify", GUEST_ORIGIN, "POST").getHeader("Access-Control-Allow-Credentials"))
                .isEqualTo("true");

        assertThat(preflight("/api/v1/feedback", ADMIN_ORIGIN, "POST").getStatus())
                .isEqualTo(403);
        assertThat(preflight("/api/v1/bots", ADMIN_ORIGIN, "GET").getStatus()).isEqualTo(403);
    }

    private MockHttpServletResponse preflight(String route, String origin, String method) throws Exception {
        return mvc.perform(options(route)
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", method)
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type, Idempotency-Key"))
                .andReturn()
                .getResponse();
    }
}
