package com.memetrade.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.tenancy.RouteRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * C8 change recorded by U2 (functional-spec.md, NFR6.7): the administrative OpenAPI group generated from the code must
 * equal the versioned apps/api/openapi/openapi-admin.yaml, hold only {@code /api/v1/admin/**} operations, and match
 * the operation block of the functional spec. The guest group never shows them (GuestOpenApiContractTest).
 * Regenerate with -Dopenapi.write=true.
 */
class AdminOpenApiContractTest extends AdminOpenApiSlice {

    static final Path VERSIONED = Path.of("openapi", "openapi-admin.yaml");

    private String generated;
    private Map<String, Object> spec;

    @BeforeEach
    void generate() throws Exception {
        generated = GuestOpenApiContractTest.normalize(generatedYaml());
        if (Boolean.getBoolean("openapi.write")) {
            Files.createDirectories(VERSIONED.getParent());
            Files.writeString(VERSIONED, generated, StandardCharsets.UTF_8);
        }
        spec = new Yaml().load(generated);
    }

    @Test
    void theGeneratedSpecificationEqualsTheVersionedFile() throws Exception {
        assertThat(Files.exists(VERSIONED))
                .as("apps/api/openapi/openapi-admin.yaml is versioned")
                .isTrue();
        assertThat(generated)
                .isEqualTo(GuestOpenApiContractTest.normalize(Files.readString(VERSIONED, StandardCharsets.UTF_8)));
    }

    @Test
    void theAdministrativeGroupHoldsOnlyAdministrativeRoutesAndItsOwnTitle() {
        assertThat(paths()).isNotEmpty();
        // u1-legal-documents: besides the administrative routes, only the public legal documents (L1), which the
        // administrator app reads too.
        assertThat(paths().keySet())
                .allMatch(path -> path.startsWith("/api/v1/admin/") || path.startsWith("/api/v1/legal/"));
        assertThat(paths().keySet())
                .contains("/api/v1/legal/current", "/api/v1/legal/versions", "/api/v1/legal/versions/{version}");
        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) spec.get("info");
        assertThat(info.get("title")).isEqualTo("MemeTrade admin API");
    }

    @Test
    void theTrailQueryHasTheParametersAndResponsesOfTheFunctionalSpec() {
        Map<String, Object> operation = operation("/api/v1/admin/audit-events", "get");

        assertThat(operation.get("operationId")).isEqualTo("listAuditEvents");
        assertThat(String.valueOf(operation.get("security"))).contains("accessToken");
        Set<String> parameters = new HashSet<>();
        Set<String> required = new HashSet<>();
        for (Object item : (List<?>) operation.get("parameters")) {
            Map<?, ?> parameter = (Map<?, ?>) item;
            parameters.add(String.valueOf(parameter.get("name")));
            if (Boolean.TRUE.equals(parameter.get("required"))) {
                required.add(String.valueOf(parameter.get("name")));
            }
        }
        assertThat(parameters).containsExactlyInAnyOrder("from", "to", "action", "cursor", "limit");
        assertThat(required).containsExactlyInAnyOrder("from", "to");
        assertThat(keys(operation.get("responses"))).containsExactlyInAnyOrder("200", "400", "401", "403", "500");
        assertThat(String.valueOf(operation.get("parameters"))).doesNotContain("Idempotency-Key");
    }

    @Test
    void theEventViewHasTheFieldsOfTheFunctionalSpecAndRejectsUndeclaredFields() {
        Map<String, Object> view = schema("AuditEventView");

        assertThat(keys(view.get("properties")))
                .containsExactlyInAnyOrder(
                        "eventId",
                        "tenantId",
                        "actor",
                        "actorType",
                        "action",
                        "targetRef",
                        "correlationId",
                        "occurredAt",
                        "recordedAt",
                        "details");
        assertThat(view.get("additionalProperties")).isEqualTo(false);
        assertThat(keys(schema("AuditEventPage").get("properties"))).containsExactlyInAnyOrder("items", "nextCursor");
    }

    /** U3 corrections and additions to C8 (functional-spec.md): code only in the 201, suffix, dates, new answers. */
    @Test
    @org.junit.jupiter.api.Tag("u3")
    void theU3InvitationRoutesFollowTheCorrectedC8() {
        Map<String, Object> invite = schema("Invite");
        assertThat(keys(invite.get("properties")))
                .contains(
                        "inviteId",
                        "tenantId",
                        "status",
                        "expiresAt",
                        "createdAt",
                        "usedAt",
                        "revokedAt",
                        "codeSuffix",
                        "code")
                .doesNotContain("issuedBy");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) invite.get("required");
        assertThat(required).doesNotContain("code").contains("codeSuffix", "createdAt", "revokedAt");
        assertThat(keys(schema("InvitePage").get("properties"))).containsExactlyInAnyOrder("items", "nextCursor");

        Map<String, Object> issue = operation("/api/v1/admin/invites", "post");
        assertThat(String.valueOf(issue.get("parameters")))
                .contains("name=Idempotency-Key")
                .contains("required=true");
        assertThat(keys(issue.get("responses"))).contains("201", "400", "401", "403", "409", "422");
        assertThat(String.valueOf(((Map<?, ?>) issue.get("responses")).get("422")))
                .contains("tenantId")
                .contains("expiresAt");
        Map<String, Object> revoke = operation("/api/v1/admin/invites/{inviteId}/revoke", "post");
        assertThat(keys(revoke.get("responses"))).contains("200", "404", "409");
        assertThat(String.valueOf(((Map<?, ?>) revoke.get("responses")).get("409")))
                .contains("INVITE_NOT_REVOCABLE");
        Map<String, Object> list = operation("/api/v1/admin/invites", "get");
        assertThat(String.valueOf(list.get("parameters")))
                .contains("name=cursor")
                .contains("name=limit")
                .contains("maximum=100");
        assertThat(keys(schema("IssueInviteRequest").get("properties")))
                .containsExactlyInAnyOrder("tenantId", "expiresAt");
    }

    @Test
    void everyAdministrativeOperationIsInTheRouteRegistryAsAdmin() throws Exception {
        Set<String> operations = new HashSet<>();
        // u1-legal-documents: the public legal documents of the group are GLOBAL routes, not administrative ones.
        paths().forEach((path, item) -> {
            if (path.startsWith("/api/v1/admin/")) {
                keys(item).forEach(method -> operations.add(method.toUpperCase() + " " + path));
            }
        });
        Set<String> registered = new HashSet<>();
        RouteRegistry.load().routes().stream()
                .filter(entry -> "ADMIN".equals(entry.scope()))
                .forEach(entry -> registered.add(entry.key()));

        assertThat(registered).containsExactlyInAnyOrderElementsOf(operations);
    }

    @Test
    void theAdministrativeSpecificationHasNoRealMode() {
        assertThat(generated).doesNotContain("REAL").doesNotContain("name: mode");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> keys(Object map) {
        return ((Map<String, Object>) map).keySet();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paths() {
        return (Map<String, Object>) spec.get("paths");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> operation(String path, String method) {
        return (Map<String, Object>) ((Map<String, Object>) paths().get(path)).get(method);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> schema(String name) {
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        assertThat(schemas).as("schema " + name).containsKey(name);
        return (Map<String, Object>) schemas.get(name);
    }
}
