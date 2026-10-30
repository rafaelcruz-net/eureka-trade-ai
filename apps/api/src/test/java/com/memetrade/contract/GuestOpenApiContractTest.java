package com.memetrade.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * NFR12.3: the OpenAPI generated from the code must equal the versioned apps/api/openapi/openapi-guest.yaml, and it
 * must match the C1 and minimal C2 blocks of contract-summary.md. Regenerate with -Dopenapi.write=true.
 */
class GuestOpenApiContractTest extends GuestOpenApiSlice {

    static final Path VERSIONED = Path.of("openapi", "openapi-guest.yaml");

    private String generated;
    private Map<String, Object> spec;

    @BeforeEach
    void generate() throws Exception {
        generated = normalize(generatedYaml());
        if (Boolean.getBoolean("openapi.write")) {
            Files.createDirectories(VERSIONED.getParent());
            Files.writeString(VERSIONED, generated, StandardCharsets.UTF_8);
        }
        spec = new Yaml().load(generated);
    }

    @Test
    void theGeneratedSpecificationEqualsTheVersionedFile() throws Exception {
        assertThat(Files.exists(VERSIONED))
                .as("apps/api/openapi/openapi-guest.yaml is versioned")
                .isTrue();
        assertThat(generated).isEqualTo(normalize(Files.readString(VERSIONED, StandardCharsets.UTF_8)));
    }

    @Test
    void c1RoutesExistAndTheChallengeRequestRejectsUndeclaredFields() {
        assertThat(paths())
                .containsKeys(
                        "/api/v1/auth/challenge", "/api/v1/auth/verify", "/api/v1/auth/refresh", "/api/v1/auth/logout");
        Map<String, Object> challenge = schema("ChallengeRequest");
        assertThat(challenge.get("required")).isEqualTo(List.of("walletAddress"));
        assertThat(challenge.get("additionalProperties")).isEqualTo(false);
        assertThat(strings(schema("VerifyRequest").get("required")))
                .containsExactlyInAnyOrder("walletAddress", "message", "signature", "provider");
        assertThat(strings(schema("GuestSession").get("required")))
                .containsExactlyInAnyOrder("accessToken", "accessTokenExpiresAt", "sessionExpiresAt", "role");
        assertThat(schema("GuestSession").toString()).doesNotContain("refreshToken");
    }

    @Test
    void stateChangingRoutesRequireTheIdempotencyKeyExceptRefresh() {
        for (String route : List.of("/api/v1/auth/challenge", "/api/v1/auth/verify", "/api/v1/auth/logout")) {
            assertThat(String.valueOf(operation(route, "post").get("parameters")))
                    .as(route)
                    .contains("name=Idempotency-Key")
                    .contains("required=true");
        }
        assertThat(operation("/api/v1/auth/refresh", "post").get("parameters")).isNull();
    }

    @Test
    void theMarketValueIsAOneOfDiscriminatedByStatus() {
        Map<String, Object> marketValue = schema("MarketValue");

        assertThat(marketValue).containsKey("oneOf");
        @SuppressWarnings("unchecked")
        Map<String, Object> discriminator = (Map<String, Object>) marketValue.get("discriminator");
        assertThat(discriminator.get("propertyName")).isEqualTo("status");
        assertThat(keys(discriminator.get("mapping"))).containsExactlyInAnyOrder("KNOWN", "UNKNOWN");
        assertThat(strings(schema("KnownValue").get("required")))
                .contains("status", "value", "source", "observedAt", "stale");
    }

    @Test
    void theMinimalScannerRowHasTheFiveMarketFieldsAndNoRisk() {
        Map<String, Object> row = schema("ScannerRow");

        assertThat(keys(row.get("properties")))
                .contains(
                        "chain",
                        "mint",
                        "symbol",
                        "coverage",
                        "price",
                        "liquidity",
                        "volume24h",
                        "holders",
                        "ageMinutes")
                .doesNotContain("risk");
        assertThat(strings(schema("ScannerPage").get("required")))
                .contains("items", "nextCursor", "total", "sources", "excludedByUnknown", "pendingNewTokens");
    }

    @Test
    void everyOperationOfTheSpecificationIsInTheTenancyRouteRegistry() throws Exception {
        java.util.Set<String> operations = new java.util.HashSet<>();
        paths().forEach((path, item) ->
                keys(item).forEach(method -> operations.add(method.toUpperCase() + " " + path)));

        // Administrative routes (scope ADMIN, U2) live in openapi-admin.yaml, checked by AdminOpenApiContractTest.
        java.util.Set<String> registered = new java.util.HashSet<>();
        com.memetrade.tenancy.RouteRegistry.load().routes().stream()
                .filter(entry -> !"ADMIN".equals(entry.scope()))
                .forEach(entry -> registered.add(entry.key()));

        assertThat(registered).containsExactlyInAnyOrderElementsOf(operations);
    }

    @Test
    void theGuestSpecificationHasNoAdministrativeRouteAndNoRealMode() {
        assertThat(paths().keySet()).noneMatch(path -> path.startsWith("/api/v1/admin"));
        assertThat(generated).doesNotContain("REAL").doesNotContain("name: mode");
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<String> keys(Object map) {
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

    /** LF line endings and a trailing newline; the servers block depends on the random test port. */
    static String normalize(String yaml) {
        String lf = yaml.replace("\r\n", "\n").replaceAll("(?m)^servers:\\n(?:[ -].*\\n)*", "");
        return lf.endsWith("\n") ? lf : lf + "\n";
    }
}
