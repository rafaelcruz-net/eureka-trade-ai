package com.memetrade.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** BR2.3: every route and job is classified, and every CLIENT entry has a cross-tenant negative test. */
@Tag("u1")
class RouteRegistryTest {

    @Test
    void everyDeclaredRouteIsClassifiedAndEveryRegistryRouteExists() throws Exception {
        Set<String> registered = RouteRegistry.load().routes().stream()
                .map(RouteRegistry.Entry::key)
                .collect(Collectors.toSet());

        assertThat(registered).containsExactlyInAnyOrderElementsOf(RouteRegistry.declaredRoutes());
    }

    @Test
    void everyScheduledJobIsClassified() throws Exception {
        Set<String> registered = RouteRegistry.load().jobs().stream()
                .map(RouteRegistry.Entry::key)
                .collect(Collectors.toSet());

        assertThat(registered).containsExactlyInAnyOrderElementsOf(RouteRegistry.declaredJobs());
    }

    @Test
    void everyClientRouteAndJobHasANegativeCrossTenantTest() throws Exception {
        assertThat(RouteRegistry.load().clientEntriesWithoutNegativeTest()).isEmpty();
    }

    /**
     * U1 started without CLIENT routes; U3 adds the first one, the caller's own account (NFR5.1); U7 adds the PAPER
     * ledger and P&L routes of C5, each with its cross-tenant negative test.
     */
    @Test
    void theClientRoutesAreTheAccountOfU3AndTheLedgerOfU7AndEveryScopeIsKnown() throws Exception {
        RouteRegistry registry = RouteRegistry.load();

        assertThat(registry.routes())
                .filteredOn(entry -> "CLIENT".equals(entry.scope()))
                .extracting(RouteRegistry.Entry::key)
                .containsExactlyInAnyOrder(
                        "GET /api/v1/account",
                        "GET /api/v1/bots/{botId}/trades",
                        "GET /api/v1/bots/{botId}/day-usage",
                        "GET /api/v1/ledger",
                        "GET /api/v1/pnl",
                        "GET /api/v1/reconciliation",
                        "POST /api/v1/reconciliation/run",
                        // U6 (C21): refused orders of the caller's bot.
                        "GET /api/v1/bots/{botId}/rejections",
                        // U5 (C4 and C7): the bots and the live channel of the caller.
                        "GET /api/v1/stream/dashboard",
                        "GET /api/v1/bots",
                        "POST /api/v1/bots",
                        "GET /api/v1/bots/{botId}",
                        "PUT /api/v1/bots/{botId}/draft",
                        "POST /api/v1/bots/{botId}/commands/{command}",
                        "POST /api/v1/bots/{botId}/limits",
                        "GET /api/v1/bots/{botId}/decisions",
                        // U8 (C6): the comment of the caller.
                        "POST /api/v1/feedback",
                        // u3-reacceptance (L2): the acceptance of the current terms by the caller.
                        "POST /api/v1/terms/acceptances",
                        // u4-data-requests (P1): the data subject requests of the caller.
                        "GET /api/v1/privacy/requests",
                        "POST /api/v1/privacy/requests",
                        "GET /api/v1/privacy/requests/{requestId}",
                        // 261113-trading-real-bot U1 (R2): the bot wallet of the caller.
                        "POST /api/v1/real/consents/challenge",
                        "GET /api/v1/real/bot-wallet",
                        // 261113-trading-real-bot U6: the read of the release of the caller.
                        "GET /api/v1/real/release",
                        "POST /api/v1/real/bot-wallet",
                        "PATCH /api/v1/real/bot-wallet/preferences",
                        "POST /api/v1/real/bot-wallet/resume",
                        "GET /api/v1/real/bot-wallet/deposits",
                        "GET /api/v1/real/bot-wallet/withdrawals",
                        "POST /api/v1/real/bot-wallet/withdrawals",
                        "GET /api/v1/real/bot-wallet/withdrawals/{withdrawalId}",
                        // 261113-trading-real-bot U4 (R3): the REAL orders, mode, liquidation and P&L of the caller.
                        "GET /api/v1/real/orders",
                        "GET /api/v1/real/orders/{realOrderId}",
                        "GET /api/v1/real/orders/{realOrderId}/signing-session",
                        "POST /api/v1/real/orders/{realOrderId}/signing-session",
                        "POST /api/v1/real/orders/{realOrderId}/reject",
                        "PUT /api/v1/real/mode",
                        "POST /api/v1/real/liquidations",
                        "GET /api/v1/real/pnl",
                        // 261113-trading-real-bot U7 (R5): the conditional exits and the fiscal export of the caller.
                        "POST /api/v1/real/conditional-orders",
                        "GET /api/v1/real/conditional-orders",
                        "GET /api/v1/real/conditional-orders/quote",
                        "POST /api/v1/real/conditional-orders/{conditionalOrderId}/signature",
                        "DELETE /api/v1/real/conditional-orders/{conditionalOrderId}",
                        "GET /api/v1/real/exports/fiscal",
                        // 261121-paper-real-unico U1 (C2, C3, C4): the REAL eligibility, the REAL limits of the
                        // account and the P&L by mode of the caller.
                        "GET /api/v1/bots/real-eligibility",
                        "GET /api/v1/real/account-limits",
                        "GET /api/v1/pnl/by-mode");
        assertThat(registry.routes())
                .extracting(RouteRegistry.Entry::scope)
                .allMatch(s -> Set.of("GLOBAL", "SESSION", "ADMIN", "CLIENT").contains(s));
    }

    /** U3 (NFR5.2): tenantFromClient is a named exception of ADMIN routes only; today only the issuance has it. */
    @Test
    @org.junit.jupiter.api.Tag("u3")
    void theTenantFromClientMarkIsOnlyOnTheInvitationIssuanceAndOnlyInAdminEntries() throws Exception {
        RouteRegistry registry = RouteRegistry.load();

        assertThat(registry.tenantFromClientOutsideAdmin()).isEmpty();
        assertThat(registry.tenantFromClientRoutes()).containsExactly("POST /api/v1/admin/invites");
    }

    @Test
    @org.junit.jupiter.api.Tag("u3")
    void aGlobalOrClientEntryWithTheTenantFromClientMarkFailsTheCheck() {
        RouteRegistry registry = RouteRegistry.of(
                List.of(
                        new RouteRegistry.Entry("GET /api/v1/x", "GLOBAL", null, true),
                        new RouteRegistry.Entry("GET /api/v1/y", "CLIENT", "a#b", true),
                        new RouteRegistry.Entry("POST /api/v1/admin/z", "ADMIN", "a#b", true)),
                List.of(new RouteRegistry.Entry("SomeJob", "GLOBAL", null, true)));

        assertThat(registry.tenantFromClientOutsideAdmin())
                .containsExactly("GET /api/v1/x", "GET /api/v1/y", "SomeJob");
    }

    @Test
    @org.junit.jupiter.api.Tag("u3")
    void theFiveU3RoutesAreRegisteredWithTheirScopesAndNegativeTests() throws Exception {
        RouteRegistry registry = RouteRegistry.load();

        assertThat(registry.routes())
                .filteredOn(
                        entry -> entry.key().contains("/invites") || entry.key().contains("/account"))
                .extracting(RouteRegistry.Entry::key, RouteRegistry.Entry::scope)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("GET /api/v1/invites/{code}", "GLOBAL"),
                        org.assertj.core.groups.Tuple.tuple("GET /api/v1/account", "CLIENT"),
                        // 261121-paper-real-unico U1 (C3): matched by "/account" too; a CLIENT route of its own.
                        org.assertj.core.groups.Tuple.tuple("GET /api/v1/real/account-limits", "CLIENT"),
                        org.assertj.core.groups.Tuple.tuple("GET /api/v1/admin/invites", "ADMIN"),
                        org.assertj.core.groups.Tuple.tuple("POST /api/v1/admin/invites", "ADMIN"),
                        org.assertj.core.groups.Tuple.tuple("POST /api/v1/admin/invites/{inviteId}/revoke", "ADMIN"));
        assertThat(registry.clientEntriesWithoutNegativeTest()).isEmpty();
        assertThat(registry.adminEntriesWithoutNegativeTest()).isEmpty();
    }

    @Test
    void theCheckFailsForAClientRouteWithoutOrWithAMissingNegativeTest() {
        RouteRegistry registry = RouteRegistry.of(
                List.of(
                        new RouteRegistry.Entry("GET /api/v1/bots/{id}", "CLIENT", null),
                        new RouteRegistry.Entry("GET /api/v1/orders/{id}", "CLIENT", "com.memetrade.Nope#missing"),
                        new RouteRegistry.Entry(
                                "GET /api/v1/ok/{id}",
                                "CLIENT",
                                "com.memetrade.tenancy.RouteRegistryTest#theCheckFailsForAClientRouteWithoutOrWithAMissingNegativeTest")),
                List.of(new RouteRegistry.Entry("BotCycleJob", "CLIENT", null)));

        assertThat(registry.clientEntriesWithoutNegativeTest())
                .containsExactly("GET /api/v1/bots/{id}", "GET /api/v1/orders/{id}", "BotCycleJob");
    }

    /**
     * U2 adds the first administrative route (C8 operation of the AuditTrail). The guest OpenAPI still has none
     * (GuestOpenApiContractTest); every administrative route is classified ADMIN with an existing negative test.
     */
    @Test
    void everyAdministrativeRouteIsClassifiedAdminAndHasANegativeTest() throws Exception {
        RouteRegistry registry = RouteRegistry.load();
        Set<String> declaredAdmin = RouteRegistry.declaredRoutes().stream()
                .filter(route -> route.contains(" /api/v1/admin/"))
                .collect(Collectors.toSet());
        Set<String> registeredAdmin = registry.routes().stream()
                .filter(entry -> "ADMIN".equals(entry.scope()))
                .map(RouteRegistry.Entry::key)
                .collect(Collectors.toSet());

        assertThat(declaredAdmin).isNotEmpty().isEqualTo(registeredAdmin);
        assertThat(registry.adminEntriesWithoutNegativeTest()).isEmpty();
        assertThat(RouteRegistry.of(List.of(new RouteRegistry.Entry("GET /api/v1/admin/x", "ADMIN", null)), List.of())
                        .adminEntriesWithoutNegativeTest())
                .containsExactly("GET /api/v1/admin/x");
    }
}
