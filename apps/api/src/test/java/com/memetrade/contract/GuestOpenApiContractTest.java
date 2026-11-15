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

    /** U1 shipped the five market fields without risk; U4 completes C2 with the risk summary of each row. */
    @Test
    void theScannerRowHasTheFiveMarketFieldsAndSinceU4TheRisk() {
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
                        "ageMinutes",
                        "risk");
        assertThat(strings(schema("ScannerPage").get("required")))
                .contains("items", "nextCursor", "total", "sources", "excludedByUnknown", "pendingNewTokens");
    }

    /** 261113-trading-real-bot U2 (BR1.8): the scanner page and the detail carry the kind of source and attribution. */
    @Test
    void theScannerAndTheDetailCarryTheDataSourceWithItsAttribution() {
        assertThat(strings(schema("ScannerPage").get("required"))).contains("dataSource");
        assertThat(strings(schema("Assessment").get("required"))).contains("dataSource");
        assertThat(strings(schema("DataSource").get("required"))).containsExactly("kind");
        assertThat(String.valueOf(schema("DataSource"))).contains("REAL").contains("SIMULATED");
        assertThat(strings(schema("Attribution").get("required"))).containsExactlyInAnyOrder("text", "url");
    }

    /** U4 additions to C2 (functional-spec.md, "Acréscimos a contratos"). */
    @Test
    @org.junit.jupiter.api.Tag("u4")
    void theU4AdditionsToC2AreInTheSpecification() {
        assertThat(String.valueOf(schema("SourceStatus"))).contains("PENDING");
        assertThat(strings(schema("SourceStatus").get("required"))).doesNotContain("lastUpdatedAt");
        assertThat(keys(schema("Assessment").get("properties")))
                .contains("computedAt", "bandRaisedByUnknown", "inValidation", "decisionHint", "factors");
        assertThat(keys(schema("SourceUnavailableProblem").get("properties"))).contains("sources", "code");
        assertThat(keys(schema("KnownValue").get("properties"))).contains("divergence");
        assertThat(schema("RiskSummary")).containsKey("oneOf");
        assertThat(String.valueOf(operation("/api/v1/scanner/tokens", "get").get("responses")))
                .contains("429")
                .contains("503");
        assertThat(paths()).containsKey("/api/v1/tokens/{chain}/{mint}/assessment");
        assertThat(String.valueOf(operation("/api/v1/scanner/tokens", "get").get("parameters")))
                .contains("name=minLiquidity")
                .contains("name=maxRiskScore")
                .doesNotContain("name=parameters");
    }

    /** U3 corrections and additions to C1 and C3 (functional-spec.md "Acréscimos e correções a contratos"). */
    @Test
    @org.junit.jupiter.api.Tag("u3")
    void theU3AdditionsToC1AndC3AreInTheSpecification() {
        Map<String, Object> verify = schema("VerifyRequest");
        assertThat(keys(verify.get("properties"))).contains("inviteCode", "acceptedTermsVersion");
        assertThat(strings(verify.get("required"))).doesNotContain("inviteCode", "acceptedTermsVersion");
        assertThat(verify.get("additionalProperties")).isEqualTo(false);
        assertThat(String.valueOf(verify)).contains("maxLength=32").doesNotContain("tenantId");
        Map<String, Object> verifyResponses = responses("/api/v1/auth/verify", "post");
        assertThat(verifyResponses.keySet()).contains("401", "403", "404", "409", "422");
        assertThat(String.valueOf(verifyResponses.get("409"))).contains("WALLET_ALREADY_LINKED");
        assertThat(String.valueOf(verifyResponses.get("422")))
                .contains("INVITE_INVALID")
                .contains("TENANT_INACTIVE")
                .contains("TERMS_NOT_ACCEPTED");
        assertThat(String.valueOf(verifyResponses.get("404"))).contains("INVITE_NOT_FOUND");

        Map<String, Object> lookup = operation("/api/v1/invites/{code}", "get");
        assertThat(lookup.get("security")).isNull();
        assertThat(responses("/api/v1/invites/{code}", "get").keySet()).containsExactlyInAnyOrder("200", "404", "429");
        assertThat(String.valueOf(responses("/api/v1/invites/{code}", "get").get("429")))
                .contains("Retry-After");
        assertThat(strings(schema("InviteLookup").get("required")))
                .containsExactlyInAnyOrder("status", "expiresAt", "termsVersion", "termsText", "riskNoticeText");
        assertThat(keys(schema("InviteLookup").get("properties"))).doesNotContain("tenantId", "inviteId", "code");

        assertThat(String.valueOf(operation("/api/v1/account", "get").get("security")))
                .contains("accessToken");
        Map<String, Object> account = schema("Account");
        assertThat(strings(account.get("required")))
                .containsExactlyInAnyOrder("walletAddressMasked", "provider", "sessionExpiresAt", "realMode");
        assertThat(String.valueOf(account.get("properties")))
                .contains("UNAVAILABLE")
                .contains("acceptedTerms");
        assertThat(strings(schema("AcceptedTerms").get("required")))
                .contains("version", "acceptedAt", "termsText", "riskNoticeText");
        assertThat(operation("/api/v1/account", "get").get("parameters")).isNull();
    }

    /** U7 (C5, functional-spec.md "Acréscimos e correções a contratos"; NFR6.3). */
    @Test
    @org.junit.jupiter.api.Tag("u7")
    void theRoutesOfC5AndTheirU7AdditionsAreInTheSpecification() {
        assertThat(paths())
                .containsKeys(
                        "/api/v1/bots/{botId}/trades",
                        "/api/v1/bots/{botId}/day-usage",
                        "/api/v1/ledger",
                        "/api/v1/pnl",
                        "/api/v1/reconciliation",
                        "/api/v1/reconciliation/run");
        assertThat(String.valueOf(schema("LedgerEntry"))).contains("FUNDING").contains("COMPENSATION");
        assertThat(keys(schema("PaperTrade").get("properties")))
                .contains(
                        "requestedQuantity",
                        "filledQuantity",
                        "status",
                        "notFilledReason",
                        "spreadPct",
                        "impactPct",
                        "liquidityConsumedPct",
                        "marketSource",
                        "marketObservedAt",
                        "limitsVersion",
                        "idempotencyKey");
        assertThat(strings(schema("PnlReport").get("required"))).contains("empty", "unrealizedAsOf", "partial");
        assertThat(schema("PnlAmount")).containsKey("oneOf");
        assertThat(String.valueOf(schema("PnlUnknown")))
                .contains("PRICE_UNKNOWN")
                .contains("NO_LIQUIDITY");
        assertThat(String.valueOf(schema("ReconciliationDivergence")))
                .contains("POSITION_MISMATCH")
                .contains("SUMMARY_MISMATCH");
        assertThat(keys(schema("DayUsage").get("properties")))
                .containsExactlyInAnyOrder("exposure", "dayLoss", "utcDay");
        assertThat(String.valueOf(
                        operation("/api/v1/reconciliation/run", "post").get("parameters")))
                .contains("name=Idempotency-Key")
                .contains("required=true");
        assertThat(String.valueOf(
                        responses("/api/v1/reconciliation/run", "post").get("429")))
                .contains("Retry-After");
    }

    /** U7 (BR4.3, NFR6.3): no route changes or deletes the ledger; only "check now" is a POST, and it only reads. */
    @Test
    @org.junit.jupiter.api.Tag("u7")
    void noWriteVerbExistsUnderTheLedgerThePnlAndTheTrades() {
        paths().forEach((path, item) -> {
            if (path.startsWith("/api/v1/ledger")
                    || path.startsWith("/api/v1/pnl")
                    || path.contains("/trades")
                    || path.contains("/day-usage")
                    || path.equals("/api/v1/reconciliation")) {
                assertThat(keys(item)).as(path).containsExactly("get");
            }
        });
        assertThat(keys(paths().get("/api/v1/reconciliation/run"))).containsExactly("post");
    }

    /** U6 (C21 with the additive fields of Q10; BR4.1, BR4.2; NFR6.2): only GET, 404 and 400 documented. */
    @Test
    @org.junit.jupiter.api.Tag("u6")
    void theRejectionsRouteOfC21IsOnlyGetWithItsAdditiveFields() {
        String route = "/api/v1/bots/{botId}/rejections";
        assertThat(paths()).containsKey(route);
        assertThat(keys(paths().get(route))).containsExactly("get");
        assertThat(String.valueOf(operation(route, "get").get("security"))).contains("accessToken");
        assertThat(responses(route, "get").keySet()).contains("200", "400", "404");
        assertThat(String.valueOf(operation(route, "get").get("parameters")))
                .contains("name=cursor")
                .contains("name=limit")
                .doesNotContain("name=parameters")
                .doesNotContain("tenantId");
        assertThat(strings(schema("RejectionPage").get("required")))
                .contains("items", "nextCursor", "total", "totalCapped");
        Map<String, Object> rejection = schema("OrderRejection");
        assertThat(strings(rejection.get("required")))
                .contains("rejectionId", "decisionId", "reasons", "limitsVersion", "evaluatedAt")
                .contains("pauseTriggers", "details", "market");
        assertThat(String.valueOf(rejection.get("properties")))
                .contains("INSUFFICIENT_PAPER_BALANCE")
                .contains("DRAWDOWN")
                .contains("DAILY_LOSS");
        assertThat(strings(schema("RejectionReasonDetail").get("required")))
                .containsExactlyInAnyOrder("reason", "measured", "limit", "unit");
        assertThat(String.valueOf(schema("RejectionMarket").get("properties")))
                .contains("MarketValue")
                .contains("estimatedSlippagePct")
                .contains("orderValue");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responses(String path, String method) {
        return (Map<String, Object>) operation(path, method).get("responses");
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
        // 261113-trading-real-bot: the REAL exists only under /api/v1/real/ (contract-summary.md "Modo"); every other
        // route stays PAPER and no route takes a "mode" query parameter.
        paths().forEach((path, item) -> {
            if (!path.startsWith("/api/v1/real/")) {
                assertThat(String.valueOf(item)).as(path).doesNotContain("REAL");
            }
        });
        assertThat(generated).doesNotContain("name: mode");
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
