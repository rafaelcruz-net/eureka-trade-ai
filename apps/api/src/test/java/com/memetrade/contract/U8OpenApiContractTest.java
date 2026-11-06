package com.memetrade.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Passo 13 of U8 (contract-summary.md C6 and C8 with the U8 additions): the versioned specifications, regenerated from
 * the code and compared with it by {@code GuestOpenApiContractTest} and {@code AdminOpenApiContractTest}, carry the
 * administrator entry, the kill switch, the system status as a union per part, the tenants, the feedback list without
 * the person, and the guest submission with 429; the guest specification has no administrative route at all, so the
 * types generated for the guest app never contain one (ADR-009).
 */
@Tag("u8")
class U8OpenApiContractTest {

    private Map<String, Object> admin;
    private Map<String, Object> guest;
    private String guestText;

    @BeforeEach
    void read() throws IOException {
        admin = new Yaml().load(Files.readString(Path.of("openapi", "openapi-admin.yaml"), StandardCharsets.UTF_8));
        guestText = Files.readString(Path.of("openapi", "openapi-guest.yaml"), StandardCharsets.UTF_8);
        guest = new Yaml().load(guestText);
    }

    @Test
    void theAdministrativeSpecificationHasEveryRouteOfU8() {
        assertThat(paths(admin))
                .containsKeys(
                        "/api/v1/admin/auth/challenge",
                        "/api/v1/admin/auth/verify",
                        "/api/v1/admin/auth/refresh",
                        "/api/v1/admin/auth/logout",
                        "/api/v1/admin/kill-switch",
                        "/api/v1/admin/system-status",
                        "/api/v1/admin/tenants",
                        "/api/v1/admin/feedback");
        assertThat(keys(paths(admin).get("/api/v1/admin/kill-switch"))).containsExactlyInAnyOrder("get", "put");
    }

    @Test
    void theStateChangingAdministrativeRoutesRequireTheIdempotencyKeyExceptRefresh() {
        for (String route :
                List.of("/api/v1/admin/auth/challenge", "/api/v1/admin/auth/verify", "/api/v1/admin/auth/logout")) {
            assertThat(String.valueOf(operation(admin, route, "post").get("parameters")))
                    .as(route)
                    .contains("name=Idempotency-Key")
                    .contains("required=true");
        }
        assertThat(String.valueOf(
                        operation(admin, "/api/v1/admin/kill-switch", "put").get("parameters")))
                .contains("name=Idempotency-Key");
        assertThat(operation(admin, "/api/v1/admin/auth/refresh", "post").get("parameters"))
                .isNull();
    }

    @Test
    void theKillSwitchCarriesPendingBotsReconcileNeededAndActiveBotsToPause() {
        assertThat(keys(properties("KillSwitch")))
                .contains(
                        "state",
                        "changedBy",
                        "changedAt",
                        "reason",
                        "pendingBots",
                        "reconcileNeeded",
                        "version",
                        "activeBotsToPause");
        assertThat(keys(properties("KillSwitchRequest"))).containsExactlyInAnyOrder("state", "reason");
        assertThat(String.valueOf(properties("KillSwitchRequest").get("reason")))
                .contains("maxLength=500");
    }

    /** BR3.3: each part is OK with value or UNKNOWN with reason; rejectedOrdersToday is the U8 addition. */
    @Test
    void theSystemStatusIsAUnionPerPart() {
        assertThat(keys(properties("SystemStatus")))
                .containsExactlyInAnyOrder(
                        "generatedAt",
                        "guestCount",
                        "inviteCounts",
                        "botCountsByState",
                        "killSwitch",
                        "sources",
                        "auditOutboxPending",
                        "rejectedOrdersToday");
        for (String part : List.of("CountPart", "CountMapPart", "KillSwitchPart", "SourcesPart")) {
            assertThat(keys(properties(part))).as(part).containsExactlyInAnyOrder("status", "value", "reason");
            assertThat(String.valueOf(properties(part).get("status")))
                    .as(part)
                    .contains("OK")
                    .contains("UNKNOWN");
            assertThat(String.valueOf(properties(part).get("reason")))
                    .as(part)
                    .contains("TIMEOUT")
                    .contains("UNAVAILABLE");
        }
    }

    /** BR6.6: the feedback item has the tenant name and never the person nor the wallet. */
    @Test
    void theFeedbackItemHasTheTenantNameAndNeverThePerson() {
        assertThat(keys(properties("FeedbackItem")))
                .containsExactlyInAnyOrder("feedbackId", "tenantId", "tenantName", "screen", "text", "createdAt")
                .doesNotContain("userId", "walletAddress");
        assertThat(keys(properties("TenantOption"))).containsExactlyInAnyOrder("tenantId", "name");
        assertThat(String.valueOf(properties("AdminSession").get("role"))).contains("ADMIN");
    }

    /** C6 with the U8 addition 429; the guest specification has no administrative route (ADR-009). */
    @Test
    void theGuestSpecificationHasTheSubmissionAndNoAdministrativeRoute() {
        Map<String, Object> send = operation(guest, "/api/v1/feedback", "post");

        assertThat(keys(send.get("responses"))).contains("201", "400", "403", "409", "429");
        assertThat(keys(paths(guest))).noneMatch(path -> path.startsWith("/api/v1/admin"));
        assertThat(guestText).doesNotContain("/api/v1/admin").doesNotContain("KillSwitch");
        assertThat(keys(properties(guest, "FeedbackRequest"))).containsExactlyInAnyOrder("screen", "text");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paths(Map<String, Object> spec) {
        return (Map<String, Object>) spec.get("paths");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> operation(Map<String, Object> spec, String path, String method) {
        return (Map<String, Object>) ((Map<String, Object>) paths(spec).get(path)).get(method);
    }

    private Map<String, Object> properties(String schema) {
        return properties(admin, schema);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> spec, String schema) {
        Map<String, Object> schemas =
                (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
        assertThat(schemas).as(schema).containsKey(schema);
        return (Map<String, Object>) ((Map<String, Object>) schemas.get(schema)).get("properties");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> keys(Object map) {
        return ((Map<String, Object>) map).keySet();
    }
}
