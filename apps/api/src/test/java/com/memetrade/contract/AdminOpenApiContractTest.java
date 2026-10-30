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
        assertThat(paths().keySet()).allMatch(path -> path.startsWith("/api/v1/admin/"));
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

    @Test
    void everyAdministrativeOperationIsInTheRouteRegistryAsAdmin() throws Exception {
        Set<String> operations = new HashSet<>();
        paths().forEach((path, item) ->
                keys(item).forEach(method -> operations.add(method.toUpperCase() + " " + path)));
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
