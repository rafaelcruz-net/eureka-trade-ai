package com.memetrade.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Passo 15 of U5: the versioned guest OpenAPI carries C4 and C7 with the U5 additions (contract-summary.md), so the
 * frontend types are generated from it (team.md: never written by hand).
 */
@Tag("u5")
class U5OpenApiContractTest extends GuestOpenApiSlice {

    private Map<String, Object> spec;

    @BeforeEach
    void read() throws Exception {
        spec = new Yaml().load(Files.readString(Path.of("openapi", "openapi-guest.yaml"), StandardCharsets.UTF_8));
    }

    @Test
    void theRoutesOfC4AndC7AreVersioned() {
        assertThat(paths())
                .containsKeys(
                        "/api/v1/strategy-templates",
                        "/api/v1/bots",
                        "/api/v1/bots/{botId}",
                        "/api/v1/bots/{botId}/draft",
                        "/api/v1/bots/{botId}/commands/{command}",
                        "/api/v1/bots/{botId}/limits",
                        "/api/v1/bots/{botId}/decisions",
                        "/api/v1/stream/dashboard");
    }

    @Test
    void theCommandsRequireTheIdempotencyKey() {
        for (String route :
                List.of("/api/v1/bots", "/api/v1/bots/{botId}/commands/{command}", "/api/v1/bots/{botId}/limits")) {
            assertThat(String.valueOf(operation(route, "post").get("parameters")))
                    .as(route)
                    .contains("name=Idempotency-Key")
                    .contains("required=true");
        }
        assertThat(String.valueOf(operation("/api/v1/bots/{botId}/draft", "put").get("parameters")))
                .contains("name=Idempotency-Key");
    }

    @Test
    void theSummaryCarriesTheU5PauseReasonsAndTheDecimalsAreText() {
        Map<String, Object> summary = schema("BotSummary");
        assertThat(String.valueOf(summary)).contains("ADMIN_RELEASED").contains("WAITING_ACTIVE_SLOT");
        Map<String, Object> limits = schema("BotLimits");
        assertThat(String.valueOf(limits.get("properties")))
                .contains("maxDrawdown")
                .contains("maxSlippage")
                .doesNotContain("maxDrawdownPct");
        assertThat(String.valueOf(((Map<?, ?>) limits.get("properties")).get("maxOrderSize")))
                .contains("type=string");
        assertThat(String.valueOf(schema("Decision").get("properties")))
                .contains("exitReason")
                .contains("requestedQuantity")
                .contains("limitsVersion");
    }

    @Test
    void theTokenRulesAreClosedToTheFiveFieldsAndTwoOperators() {
        String rule = String.valueOf(schema("TokenRule"));
        assertThat(rule)
                .contains("LIQUIDITY")
                .contains("RISK_SCORE")
                .contains("GTE")
                .contains("LTE");
        assertThat(rule).doesNotContain("EQ,").doesNotContain("EQ]");
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
        return (Map<String, Object>)
                ((Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas")).get(name);
    }
}
