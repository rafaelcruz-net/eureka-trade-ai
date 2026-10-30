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

    @Test
    void inU1TheRegistryStartsWithoutClientRoutes() throws Exception {
        RouteRegistry registry = RouteRegistry.load();

        assertThat(registry.routes()).extracting(RouteRegistry.Entry::scope).doesNotContain("CLIENT");
        assertThat(registry.routes())
                .extracting(RouteRegistry.Entry::scope)
                .allMatch(s -> Set.of("GLOBAL", "SESSION", "ADMIN").contains(s));
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
