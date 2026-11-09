package com.memetrade.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.memetrade.tenancy.RouteRegistry;
import com.memetrade.testsupport.AbstractU8IT;
import com.memetrade.testsupport.AdminSessions;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Passo 11 of U8 (BR1.5, BR4.1, AC7.4.2, NFR5.2, NFR6.3; security-design.md "Separação de papéis"): walking the route
 * registry, an ADMINISTRATOR token on every guest route is {@code 403 FORBIDDEN_ROLE} with exactly one
 * {@code REQUEST_FORBIDDEN}, and a GUEST token on every administrative route is the same. The public routes (entries,
 * invitation lookup) read no token and are left out. The administrator never reaches a bot, the ledger, the P&L or a
 * trade of a person.
 */
class AdminGuestRoutesIT extends AbstractU8IT {

    /**
     * Routes that read no access token: the entries, the public invitation lookup and the public legal documents
     * (u1-legal-documents, BR1.7).
     */
    private static final Set<String> PUBLIC = Set.of(
            "POST /api/v1/auth/challenge",
            "POST /api/v1/auth/verify",
            "POST /api/v1/auth/refresh",
            "GET /api/v1/invites/{code}",
            "GET /api/v1/legal/current",
            "GET /api/v1/legal/versions",
            "GET /api/v1/legal/versions/{version}",
            "POST /api/v1/admin/auth/challenge",
            "POST /api/v1/admin/auth/verify",
            "POST /api/v1/admin/auth/refresh");

    @Test
    void anAdministratorTokenOnEveryGuestRouteIsForbiddenAndAuditedOnce() throws Exception {
        AdminSessions.AdminSession admin = sessionOf(newAdministrator());
        List<String> guestRoutes = RouteRegistry.load().routes().stream()
                .filter(entry -> !"ADMIN".equals(entry.scope()))
                .map(RouteRegistry.Entry::key)
                .filter(key -> !PUBLIC.contains(key))
                .toList();

        assertThat(guestRoutes).contains("GET /api/v1/bots", "GET /api/v1/ledger", "POST /api/v1/feedback");
        for (String route : guestRoutes) {
            assertForbiddenOnce(route, admin.bearer());
        }
    }

    @Test
    void aGuestTokenOnEveryAdministrativeRouteIsForbiddenAndAuditedOnce() throws Exception {
        Guest guest = newGuest();
        List<String> adminRoutes = RouteRegistry.load().routes().stream()
                .filter(entry -> "ADMIN".equals(entry.scope()))
                .map(RouteRegistry.Entry::key)
                .filter(key -> !PUBLIC.contains(key))
                .toList();

        assertThat(adminRoutes)
                .contains(
                        "GET /api/v1/admin/kill-switch",
                        "PUT /api/v1/admin/kill-switch",
                        "GET /api/v1/admin/system-status",
                        "GET /api/v1/admin/tenants",
                        "GET /api/v1/admin/feedback",
                        "POST /api/v1/admin/auth/logout");
        for (String route : adminRoutes) {
            assertForbiddenOnce(route, guest.bearer());
        }
    }

    /** AC7.4.2: no administrative route reads a bot, the ledger, the P&L, a trade or a wallet. */
    @Test
    void noAdministrativeRouteReachesTheDataOfAPerson() throws Exception {
        List<String> adminRoutes = RouteRegistry.load().routes().stream()
                .filter(entry -> "ADMIN".equals(entry.scope()))
                .map(RouteRegistry.Entry::key)
                .toList();

        assertThat(adminRoutes)
                .noneMatch(key -> key.contains("/bots")
                        || key.contains("/ledger")
                        || key.contains("/pnl")
                        || key.contains("/trades")
                        || key.contains("/wallet"));
    }

    private void assertForbiddenOnce(String route, String bearer) throws Exception {
        String[] parts = route.split(" ", 2);
        String path = parts[1].replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
        long before = forbiddenEvents();

        MockHttpServletResponse response = mvc.perform(request(HttpMethod.valueOf(parts[0]), path)
                        .header("Authorization", bearer)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).as(route).isEqualTo(403);
        assertThat(body(response).get("code").asText()).as(route).isEqualTo("FORBIDDEN_ROLE");
        assertThat(forbiddenEvents() - before)
                .as("REQUEST_FORBIDDEN of " + route)
                .isEqualTo(1);
    }

    private long forbiddenEvents() {
        return jdbc.sql("select count(*) from audit_outbox where payload->>'action' = 'REQUEST_FORBIDDEN'")
                .query(Long.class)
                .single();
    }
}
