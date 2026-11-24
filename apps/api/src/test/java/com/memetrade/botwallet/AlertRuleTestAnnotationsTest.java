package com.memetrade.botwallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 261121-paper-real-unico U4, Etapa 23 (written before the rules; review R-03; NFR7.6): every alert the file of the T20
 * expects carries exactly the annotations of its rule, the summary as YAML folds it, so a change of text in the rule
 * fails here, in Java, before the promtool; and the {@code for} of the ratio equals its {@code for_seconds}.
 */
@Tag("u4-own-quantity-cycle")
class AlertRuleTestAnnotationsTest {

    private static final Path OBSERVABILITY = Path.of("..", "..", "infra", "observability");
    private static final List<String> OWN = List.of("RealOpsOwnHoldingsUnavailable", "RealOpsOwnHoldingsStuckBots");

    @Test
    @SuppressWarnings("unchecked")
    void theExpectedAnnotationsAreTheAnnotationsOfTheRules() throws Exception {
        Map<String, Map<String, Object>> rules = rules();
        assertThat(rules).containsKeys(OWN.toArray(String[]::new));
        Map<String, Object> tests = new Yaml()
                .load(Files.readString(
                        OBSERVABILITY.resolve("tests/real-ops-own-holdings-unavailable.test.yml"),
                        StandardCharsets.UTF_8));
        int expected = 0;
        for (Map<String, Object> test : (List<Map<String, Object>>) tests.get("tests")) {
            for (Map<String, Object> check : (List<Map<String, Object>>) test.get("alert_rule_test")) {
                String alert = String.valueOf(check.get("alertname"));
                assertThat(OWN).contains(alert);
                for (Map<String, Object> alertExpected : (List<Map<String, Object>>) check.get("exp_alerts")) {
                    Map<String, Object> rule = rules.get(alert);
                    assertThat(alertExpected.get("exp_annotations")).as(alert).isEqualTo(rule.get("annotations"));
                    assertThat(alertExpected.get("exp_labels")).as(alert).isEqualTo(rule.get("labels"));
                    expected++;
                }
            }
        }
        assertThat(expected).as("the cases that expect an alert").isGreaterThanOrEqualTo(9);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theForOfTheRatioEqualsItsForSecondsAndTheStuckBotsAlertHasNoFor() throws Exception {
        Map<String, Map<String, Object>> rules = rules();
        Map<String, Object> ratio = rules.get("RealOpsOwnHoldingsUnavailable");
        Map<String, Object> stuck = rules.get("RealOpsOwnHoldingsStuckBots");

        assertThat(ratio.get("for")).isEqualTo("15m");
        assertThat(((Map<String, Object>) ratio.get("annotations")).get("for_seconds"))
                .isEqualTo("900");
        assertThat(stuck).doesNotContainKey("for");
        assertThat((Map<String, Object>) stuck.get("annotations")).doesNotContainKey("for_seconds");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> rules() throws Exception {
        Map<String, Object> document =
                new Yaml().load(Files.readString(OBSERVABILITY.resolve("real-ops-alerts.yml"), StandardCharsets.UTF_8));
        Map<String, Map<String, Object>> rules = new HashMap<>();
        for (Map<String, Object> group : (List<Map<String, Object>>) document.get("groups")) {
            for (Map<String, Object> rule : (List<Map<String, Object>>) group.get("rules")) {
                rules.put(String.valueOf(rule.get("alert")), rule);
            }
        }
        return rules;
    }
}
