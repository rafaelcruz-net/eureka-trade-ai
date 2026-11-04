package com.memetrade.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Passo 14 (NFR5.2, ADR-002): the tenant never comes from the client, except on routes registered with
 * {@code tenantFromClient: true}. The scan walks the versioned guest and administrative OpenAPI (equal to the code by
 * the contract tests) and looks in every operation for a parameter (path, query, header) or a request-body property,
 * at any depth and through {@code $ref}, named {@code tenantId}.
 */
@Tag("u3")
class TenantFromClientScanTest {

    private static final List<Path> SPECIFICATIONS =
            List.of(Path.of("openapi", "openapi-guest.yaml"), Path.of("openapi", "openapi-admin.yaml"));

    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

    @Test
    void everyOperationThatReceivesATenantIdIsMarkedTenantFromClientInTheRegistry() throws IOException {
        Set<String> marked = RouteRegistry.load().tenantFromClientRoutes();
        List<String> receiving = new ArrayList<>();
        for (Path specification : SPECIFICATIONS) {
            receiving.addAll(operationsReceivingTenantId(load(specification)));
        }

        assertThat(receiving).allMatch(marked::contains);
        assertThat(marked).allMatch(receiving::contains);
    }

    @Test
    void aFictitiousOperationWithATenantIdAndNoMarkIsFound() {
        Map<String, Object> spec = new Yaml().load("""
                paths:
                  /api/v1/bots:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: { $ref: '#/components/schemas/CreateBot' }
                  /api/v1/orders:
                    get:
                      parameters:
                        - { name: tenantId, in: query, schema: { type: string } }
                  /api/v1/clean:
                    get:
                      parameters:
                        - { name: cursor, in: query }
                components:
                  schemas:
                    CreateBot:
                      type: object
                      properties:
                        settings: { $ref: '#/components/schemas/Settings' }
                    Settings:
                      type: object
                      properties:
                        nested: { type: object, properties: { tenantId: { type: string } } }
                """);

        List<String> receiving = operationsReceivingTenantId(spec);

        assertThat(receiving).containsExactlyInAnyOrder("POST /api/v1/bots", "GET /api/v1/orders");
        assertThat(receiving).noneMatch(RouteRegistry.of(List.of(), List.of()).tenantFromClientRoutes()::contains);
    }

    @Test
    void aHeaderOrPathParameterNamedTenantIdThroughARefIsFoundToo() {
        Map<String, Object> spec = new Yaml().load("""
                paths:
                  /api/v1/things/{tenantId}:
                    get:
                      parameters:
                        - { $ref: '#/components/parameters/Tenant' }
                  /api/v1/other:
                    delete:
                      parameters:
                        - { name: X-Tenant, in: header }
                        - { name: TenantId, in: header }
                components:
                  parameters:
                    Tenant: { name: tenantId, in: path, required: true }
                """);

        assertThat(operationsReceivingTenantId(spec))
                .containsExactlyInAnyOrder("GET /api/v1/things/{tenantId}", "DELETE /api/v1/other");
    }

    @SuppressWarnings("unchecked")
    static List<String> operationsReceivingTenantId(Map<String, Object> spec) {
        List<String> found = new ArrayList<>();
        Map<String, Object> paths = (Map<String, Object>) spec.getOrDefault("paths", Map.of());
        paths.forEach((path, item) -> ((Map<String, Object>) item).forEach((method, operation) -> {
            if (METHODS.contains(method) && receivesTenantId(spec, (Map<String, Object>) operation)) {
                found.add(method.toUpperCase(Locale.ROOT) + " " + path);
            }
        }));
        return found;
    }

    @SuppressWarnings("unchecked")
    private static boolean receivesTenantId(Map<String, Object> spec, Map<String, Object> operation) {
        for (Object parameter : (List<Object>) operation.getOrDefault("parameters", List.of())) {
            Map<String, Object> resolved = (Map<String, Object>) resolve(spec, parameter);
            if (isTenantId(String.valueOf(resolved.get("name")))) {
                return true;
            }
        }
        Object body = operation.get("requestBody");
        return body != null && containsTenantProperty(spec, resolve(spec, body), new HashSet<>());
    }

    @SuppressWarnings("unchecked")
    private static boolean containsTenantProperty(Map<String, Object> spec, Object node, Set<String> seenRefs) {
        if (node instanceof Map<?, ?> map) {
            Object ref = map.get("$ref");
            if (ref != null) {
                return seenRefs.add(String.valueOf(ref)) && containsTenantProperty(spec, resolve(spec, map), seenRefs);
            }
            Object properties = map.get("properties");
            if (properties instanceof Map<?, ?> props
                    && props.keySet().stream().anyMatch(key -> isTenantId(String.valueOf(key)))) {
                return true;
            }
            for (Object value : ((Map<String, Object>) map).values()) {
                if (containsTenantProperty(spec, value, seenRefs)) {
                    return true;
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object value : list) {
                if (containsTenantProperty(spec, value, seenRefs)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Follows a local {@code $ref} such as {@code #/components/schemas/X}. */
    @SuppressWarnings("unchecked")
    private static Object resolve(Map<String, Object> spec, Object node) {
        if (!(node instanceof Map<?, ?> map) || !(map.get("$ref") instanceof String ref) || !ref.startsWith("#/")) {
            return node;
        }
        Object current = spec;
        for (String part : ref.substring(2).split("/")) {
            current = ((Map<String, Object>) current).get(part);
        }
        return current;
    }

    private static boolean isTenantId(String name) {
        return name.replace("-", "").replace("_", "").equalsIgnoreCase("tenantid");
    }

    private static Map<String, Object> load(Path specification) throws IOException {
        return new Yaml().load(Files.readString(specification, StandardCharsets.UTF_8));
    }
}
