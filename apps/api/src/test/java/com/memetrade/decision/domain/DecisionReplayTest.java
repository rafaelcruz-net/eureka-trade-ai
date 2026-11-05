package com.memetrade.decision.domain;

import static com.memetrade.decision.domain.DecisionScenarios.TOKEN_A;
import static com.memetrade.decision.domain.DecisionScenarios.TOKEN_B;
import static com.memetrade.decision.domain.DecisionScenarios.TOKEN_C;
import static com.memetrade.decision.domain.DecisionScenarios.append;
import static com.memetrade.decision.domain.DecisionScenarios.repeat;
import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.decision.infra.DecisionJson;
import com.memetrade.riskassessment.api.RiskBand;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * NFR8.1, BR4.2, AC4.5.2 (Passo 7, written before the engine and the codec): for each decision of the catalogue,
 * recomputing it ONLY from what is recorded (parameters, version, limits version, inputs and context, read back from
 * their JSON text) gives the same decision, including the exact decimals and the unknown values.
 */
@Tag("u5")
class DecisionReplayTest {

    private final DecisionEngine engine = new DecisionEngine();
    private final DecisionJson json = new DecisionJson();

    private static final List<DecisionRequest> CATALOGUE = List.of(
            DecisionScenarios.momentum()
                    .token(
                            TOKEN_A,
                            new String[] {"1.00", "1.01", "1.01", "1.02"},
                            new String[] {"1000000", "1000000", "1000000", "1200000"},
                            "1.02")
                    .build(),
            DecisionScenarios.momentum()
                    .token(
                            TOKEN_A,
                            new String[] {"1.00", null, "1.01", "1.02"},
                            new String[] {"1000000", "1000000", "1000000", "1200000"},
                            "1.02")
                    .build(),
            DecisionScenarios.range()
                    .token(
                            TOKEN_A,
                            append(repeat("1.00", 24), "1.010000000000"),
                            repeat("500000", 25),
                            "1.010000000000")
                    .build(),
            DecisionScenarios.range()
                    .token(TOKEN_A, append(repeat("1.00", 24), "0.98"), repeat("500000", 25), "0.98")
                    .position(TOKEN_A, "100.000000", "100.000000", 30)
                    .build(),
            DecisionScenarios.momentum()
                    .token(
                            TOKEN_A,
                            new String[] {"1.00", "1.00", "1.00", "1.10"},
                            new String[] {"1000000", "1000000", "1000000", "1000000"},
                            "1.10")
                    .token(
                            TOKEN_B,
                            new String[] {"1.00", "1.01", "1.01", "1.02"},
                            new String[] {"1000000", "1000000", "1000000", "1200000"},
                            "1.02")
                    .position(TOKEN_A, "100.000000", "100.000000", 10)
                    .build(),
            DecisionScenarios.momentum()
                    .tokenWithBand(
                            TOKEN_C,
                            new String[] {"1.00", "1.01", "1.01", "1.02"},
                            new String[] {"1000000", "1000000", "1000000", "1200000"},
                            "1.02",
                            RiskBand.HIGH)
                    .build(),
            DecisionScenarios.momentum()
                    .token(
                            TOKEN_A,
                            new String[] {"1.00", "1.00", "1.00", "1.00"},
                            new String[] {"1000000", "1000000", "1000000", "1000000"},
                            null)
                    .position(TOKEN_A, "100.000000", "100.000000", 600)
                    .build(),
            DecisionScenarios.momentum().balance("0.000001").build());

    @Test
    void everyDecisionOfTheCatalogueIsRecomputedFromWhatWasRecorded() {
        for (DecisionRequest request : CATALOGUE) {
            DecisionOutcome original = engine.decide(request);
            DecisionJson.Recorded recorded = json.write(request, original);

            DecisionRequest replayed =
                    json.read(request.strategy(), request.strategyVersion(), request.limitsVersion(), recorded);

            assertThat(replayed).isEqualTo(request);
            assertThat(engine.decide(replayed)).isEqualTo(original);
        }
    }

    @Test
    void theRecordedInputsKeepUnknownValuesAsUnknownAndDecimalsAsText() {
        DecisionRequest request = CATALOGUE.get(1);
        DecisionJson.Recorded recorded = json.write(request, engine.decide(request));

        assertThat(recorded.inputs()).contains("\"status\":\"UNKNOWN\"").contains("\"value\":\"1.02\"");
        assertThat(recorded.inputs()).doesNotContain("\"value\":0").doesNotContain("\"value\":1.02");
        assertThat(recorded.factors()).contains("\"status\":\"UNKNOWN\"");
    }

    @Test
    void theContextKeepsTheBalanceAndThePositionsWithTheirOpeningInstant() {
        DecisionRequest request = CATALOGUE.get(3);
        DecisionJson.Recorded recorded = json.write(request, engine.decide(request));

        assertThat(recorded.context())
                .contains("\"availableBalance\":\"1000.000000\"")
                .contains("\"quantity\":\"100.000000\"")
                .contains("\"openedAt\":\"2026-10-28T11:31:00Z\"");
        assertThat(recorded.parameters()).contains("\"windowMinutes\":\"120\"");
    }

    @Test
    void theRecordIsStableTextForTheSameDecision() {
        DecisionRequest request = CATALOGUE.get(4);
        DecisionOutcome outcome = engine.decide(request);

        assertThat(json.write(request, outcome)).isEqualTo(json.write(request, outcome));
    }

    @Test
    void anEmptySelectionIsRecordedAndReplayedAsNoTokens() {
        DecisionRequest request = CATALOGUE.getLast();
        DecisionOutcome original = engine.decide(request);
        DecisionRequest replayed = json.read(
                request.strategy(), request.strategyVersion(), request.limitsVersion(), json.write(request, original));

        assertThat(engine.decide(replayed).noTradeReason()).contains(NoTradeReason.NO_TOKENS);
    }
}
