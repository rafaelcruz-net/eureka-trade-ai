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
 * Passo 4 of U9: the versioned guest OpenAPI documents the six envelopes of the live channel C7 and its {@code 429},
 * so the guest app generates their types (team.md: never written by hand). {@link GuestOpenApiContractTest} checks
 * the versioned file equals the one generated from the code.
 */
@Tag("u9")
class U9OpenApiContractTest {

    private static final List<String> ENVELOPES = List.of(
            "DashboardBotStateEvent",
            "DashboardTradeRecordedEvent",
            "DashboardPnlUpdatedEvent",
            "DashboardDataStatusEvent",
            "DashboardHeartbeatEvent",
            "DashboardAuthExpiredEvent",
            // 261113-trading-real-bot U4 (R3): the state of a REAL order and the signing session opened.
            "RealOrderStateChanged",
            "RealSigningSessionOpened");

    private Map<String, Object> spec;

    @BeforeEach
    void read() throws Exception {
        spec = new Yaml().load(Files.readString(Path.of("openapi", "openapi-guest.yaml"), StandardCharsets.UTF_8));
    }

    @Test
    void theStreamDocumentsTheSixEnvelopesAsTheDataOfItsEvents() {
        Map<String, Object> ok = map(responses().get("200"));
        Map<String, Object> schema =
                map(map(map(ok.get("content")).get("text/event-stream")).get("schema"));

        List<String> refs = ((List<?>) schema.get("oneOf"))
                .stream().map(entry -> String.valueOf(map(entry).get("$ref"))).toList();

        assertThat(refs)
                .containsExactlyElementsOf(ENVELOPES.stream()
                        .map(name -> "#/components/schemas/" + name)
                        .toList());
        assertThat(schemas()).containsKeys(ENVELOPES.toArray(String[]::new));
    }

    @Test
    void theEnvelopesCarryTheFieldsTheChannelSends() {
        assertThat(required("DashboardBotStateEvent")).containsExactlyInAnyOrder("items", "activeCount");
        assertThat(String.valueOf(map(schemas().get("DashboardBotStateEvent")).get("properties")))
                .contains("#/components/schemas/BotSummary")
                .doesNotContain("activeLimit");
        assertThat(required("DashboardTradeNotice"))
                .containsExactlyInAnyOrder("orderId", "botId", "side", "chain", "tokenMint", "status", "createdAt");
        assertThat(map(map(schemas().get("DashboardTradeNotice")).get("properties"))
                        .keySet())
                .doesNotContain("notFilledReason")
                .doesNotContain("fillPrice");
        assertThat(required("DashboardPnlUpdatedEvent")).containsExactly("ledgerMark");
        assertThat(String.valueOf(map(schemas().get("DashboardDataStatusEvent")).get("properties")))
                .contains("#/components/schemas/SourceStatus");
        assertThat(required("DashboardHeartbeatEvent")).containsExactly("at");
        assertThat(required("DashboardAuthExpiredEvent")).containsExactly("expiredAt");
    }

    @Test
    void theSixthChannelIsA429ProblemWithRetryAfter() {
        Map<String, Object> refused = map(responses().get("429"));

        assertThat(String.valueOf(refused.get("description"))).contains("RATE_LIMITED");
        assertThat(map(refused.get("headers"))).containsKey("Retry-After");
        assertThat(String.valueOf(map(map(refused.get("content")).get("application/problem+json"))))
                .contains("#/components/schemas/Problem");
        assertThat(responses()).containsKey("401");
    }

    // ----- helpers -----

    private Map<String, Object> responses() {
        Map<String, Object> paths = map(spec.get("paths"));
        return map(map(map(paths.get("/api/v1/stream/dashboard")).get("get")).get("responses"));
    }

    private Map<String, Object> schemas() {
        return map(map(spec.get("components")).get("schemas"));
    }

    private List<String> required(String schema) {
        return ((List<?>) map(schemas().get(schema)).get("required"))
                .stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
