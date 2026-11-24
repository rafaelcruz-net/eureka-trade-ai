package com.memetrade.decision.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.decision.domain.DecisionEngine;
import com.memetrade.decision.domain.DecisionOutcome;
import com.memetrade.decision.domain.DecisionRequest;
import com.memetrade.decision.domain.DecisionScenarios;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U4, Etapa 15 (after the layer; alignment A10, NFR4.1, T7, T14): the note of the own quantity
 * is written as {@code context.ownQuantity} only when given; without it the recorded JSON is today's, byte for byte;
 * {@link DecisionJson#read} ignores the key and rebuilds the same request.
 */
class DecisionJsonOwnQuantityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DecisionJson json = new DecisionJson();
    private final DecisionRequest request = DecisionScenarios.momentum()
            .token(DecisionScenarios.TOKEN_A, new String[] {"1.00", "1.00", "1.00", "1.00"}, volumes(), "1.00")
            .position(DecisionScenarios.TOKEN_A, "40", "4.00", 30)
            .build();
    private final DecisionOutcome outcome = new DecisionEngine().decide(request);

    @Test
    void withoutTheNoteTheRecordedFormIsTodaysByteForByte() {
        DecisionJson.Recorded plain = json.write(request, outcome);
        DecisionJson.Recorded again = json.write(request, outcome);

        assertThat(again).isEqualTo(plain);
        assertThat(plain.context()).doesNotContain("ownQuantity");
    }

    @Test
    void theNoteIsAddedToTheContextAndNothingElseChanges() throws Exception {
        DecisionJson.Recorded plain = json.write(request, outcome);
        DecisionJson.Recorded noted = json.write(request, outcome, context());

        assertThat(noted.parameters()).isEqualTo(plain.parameters());
        assertThat(noted.inputs()).isEqualTo(plain.inputs());
        assertThat(noted.factors()).isEqualTo(plain.factors());
        JsonNode note = MAPPER.readTree(noted.context()).get("ownQuantity");
        assertThat(note.get("read").asText()).isEqualTo("AVAILABLE");
        assertThat(note.has("reason")).isFalse();
        assertThat(note.get("mints").get(DecisionScenarios.TOKEN_A.mint()).asText())
                .isEqualTo("OWN_QUANTITY_AVAILABLE");
        assertThat(note.get("cap").get("outcome").asText()).isEqualTo("CAPPED");
        assertThat(note.get("cap").get("requestedBaseUnits").asText()).isEqualTo("150000000");
        assertThat(note.get("cap").get("sellBaseUnits").asText()).isEqualTo("100000000");
        JsonNode without = MAPPER.readTree(noted.context());
        ((com.fasterxml.jackson.databind.node.ObjectNode) without).remove("ownQuantity");
        assertThat(without).isEqualTo(MAPPER.readTree(plain.context()));
    }

    @Test
    void anUnavailableReadRecordsItsReason() throws Exception {
        OwnQuantityContext unavailable = new OwnQuantityContext(
                "UNAVAILABLE",
                Optional.of("BUDGET_EXCEEDED"),
                Map.of(DecisionScenarios.TOKEN_A.mint(), "OWN_QUANTITY_UNAVAILABLE"),
                OwnQuantityContext.Cap.NONE);

        JsonNode note = MAPPER.readTree(
                        json.write(request, outcome, unavailable).context())
                .get("ownQuantity");

        assertThat(note.get("reason").asText()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(note.get("cap").get("outcome").asText()).isEqualTo("NONE");
        assertThat(note.get("cap").has("requestedBaseUnits")).isFalse();
    }

    @Test
    void readIgnoresTheNoteAndRebuildsTheSameRequest() {
        DecisionJson.Recorded noted = json.write(request, outcome, context());

        DecisionRequest read = json.read(request.strategy(), request.strategyVersion(), request.limitsVersion(), noted);

        assertThat(read).isEqualTo(request);
    }

    private static OwnQuantityContext context() {
        Map<String, String> mints = new LinkedHashMap<>();
        mints.put(DecisionScenarios.TOKEN_A.mint(), "OWN_QUANTITY_AVAILABLE");
        return new OwnQuantityContext(
                "AVAILABLE",
                Optional.empty(),
                mints,
                new OwnQuantityContext.Cap("CAPPED", Optional.of(150_000_000L), Optional.of(100_000_000L)));
    }

    private static String[] volumes() {
        return new String[] {"1000000", "1000000", "1000000", "1000000"};
    }
}
