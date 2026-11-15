package com.memetrade.riskcontrol.real;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Step 11 of the U3 (monitoring-design.md "Paridade", U3-NFR5.9 to U3-NFR5.12): the alert rules of the REAL barrier in
 * {@code infra/observability/real-risk-alerts.yml} (Prometheus, promtool) and the CloudWatch alarms in
 * {@code infra/terraform/modules/observability/real-risk.tf} have the same name, threshold, window and job dimension,
 * rule by rule. The slow-barrier rule is the only one without an alarm, marked {@code cloud: deferred}. The test of the
 * U2 is not changed.
 */
class RealRiskAlarmParityTest {

    private static final Path ROOT = Path.of("..", "..");
    private static final Path RULES = ROOT.resolve("infra/observability/real-risk-alerts.yml");
    private static final Path ALARMS = ROOT.resolve("infra/terraform/modules/observability/real-risk.tf");

    private static final Pattern RESOURCE = Pattern.compile("resource \"aws_cloudwatch_metric_alarm\" \"(\\w+)\" \\{");
    private static final Pattern NAME = Pattern.compile("alarm_name\\s*=\\s*\"\\$\\{var\\.name}-([a-z0-9-]+)\"");
    private static final Pattern THRESHOLD = Pattern.compile("\\n\\s*threshold\\s*=\\s*([0-9.]+)");
    private static final Pattern PERIOD = Pattern.compile("\\n\\s*period\\s*=\\s*(\\d+)");
    private static final Pattern EVALUATIONS = Pattern.compile("evaluation_periods\\s*=\\s*(\\d+)");
    private static final Pattern JOB = Pattern.compile("dimensions\\s*=\\s*\\{\\s*job\\s*=\\s*\"([a-z-]+)\"\\s*}");
    private static final Pattern NAMESPACE = Pattern.compile("real_risk_namespace\\s*=\\s*\"([^\"]+)\"");

    record Rule(
            String name,
            String expr,
            String forDuration,
            BigDecimal threshold,
            long windowSeconds,
            String job,
            boolean deferred) {}

    record Alarm(String name, BigDecimal threshold, long windowSeconds, String job) {}

    @SuppressWarnings("unchecked")
    static List<Rule> rules() throws IOException {
        Map<String, Object> document = new Yaml().load(Files.readString(RULES, StandardCharsets.UTF_8));
        List<Rule> rules = new ArrayList<>();
        for (Map<String, Object> group : (List<Map<String, Object>>) document.get("groups")) {
            for (Map<String, Object> rule : (List<Map<String, Object>>) group.get("rules")) {
                Map<String, Object> annotations = (Map<String, Object>) rule.get("annotations");
                rules.add(new Rule(
                        kebab(String.valueOf(rule.get("alert"))),
                        String.valueOf(rule.get("expr")),
                        rule.containsKey("for") ? String.valueOf(rule.get("for")) : null,
                        new BigDecimal(String.valueOf(annotations.get("threshold"))),
                        Long.parseLong(String.valueOf(annotations.get("window_seconds"))),
                        annotations.containsKey("job") ? String.valueOf(annotations.get("job")) : null,
                        "deferred".equals(annotations.get("cloud"))));
            }
        }
        return rules;
    }

    static Map<String, Alarm> alarms() throws IOException {
        String terraform = Files.readString(ALARMS, StandardCharsets.UTF_8);
        Map<String, Alarm> alarms = new LinkedHashMap<>();
        Matcher resource = RESOURCE.matcher(terraform);
        List<Integer> starts = new ArrayList<>();
        while (resource.find()) {
            starts.add(resource.start());
        }
        for (int i = 0; i < starts.size(); i++) {
            String block =
                    terraform.substring(starts.get(i), i + 1 < starts.size() ? starts.get(i + 1) : terraform.length());
            String name = group(NAME, block);
            long period = Long.parseLong(group(PERIOD, block));
            long evaluations = Long.parseLong(group(EVALUATIONS, block));
            Matcher job = JOB.matcher(block);
            alarms.put(
                    name,
                    new Alarm(
                            name,
                            new BigDecimal(group(THRESHOLD, block)),
                            period * evaluations,
                            job.find() ? job.group(1) : null));
        }
        return alarms;
    }

    private static String group(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        assertThat(matcher.find()).as(pattern.pattern()).isTrue();
        return matcher.group(1);
    }

    static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    @Test
    void everyRuleButTheDeferredOneHasAnAlarmWithTheSameNameThresholdWindowAndJob() throws IOException {
        List<Rule> rules = rules();
        Map<String, Alarm> alarms = alarms();

        assertThat(rules).hasSize(6);
        List<Rule> inCloud = rules.stream().filter(rule -> !rule.deferred()).toList();
        assertThat(alarms.keySet())
                .containsExactlyInAnyOrderElementsOf(
                        inCloud.stream().map(Rule::name).toList());
        for (Rule rule : inCloud) {
            Alarm alarm = alarms.get(rule.name());
            assertThat(alarm.threshold()).as(rule.name()).isEqualByComparingTo(rule.threshold());
            assertThat(alarm.windowSeconds()).as(rule.name()).isEqualTo(rule.windowSeconds());
            assertThat(alarm.job()).as(rule.name()).isEqualTo(rule.job());
        }
    }

    @Test
    void eachExpressionUsesItsOwnThresholdWindowAndJob() throws IOException {
        for (Rule rule : rules()) {
            assertThat(rule.expr()).as(rule.name()).contains(rule.threshold().toPlainString());
            assertThat(rule.expr()).as(rule.name()).contains("[" + (rule.windowSeconds() / 60) + "m]");
            if (rule.forDuration() != null) {
                assertThat(rule.forDuration()).as(rule.name()).isEqualTo((rule.windowSeconds() / 60) + "m");
            }
            if (rule.job() != null) {
                assertThat(rule.expr())
                        .as(rule.name())
                        .contains("real_risk_job_runs_total{job=\"" + rule.job() + "\"}")
                        .contains("absent(real_risk_job_runs_total{job=\"" + rule.job() + "\"})");
            }
        }
    }

    @Test
    void onlyTheSlowBarrierIsDeferredAndTheAlarmsUseTheNamespaceOfTheU3() throws IOException {
        assertThat(rules()).filteredOn(Rule::deferred).extracting(Rule::name).containsExactly("real-risk-slow");
        assertThat(group(NAMESPACE, Files.readString(ALARMS, StandardCharsets.UTF_8)))
                .isEqualTo("Eureka/RealRisk");
    }

    @Test
    void theStalledJobsAlarmWhenNothingIsPublishedAndNoLabelCarriesAMintOrAPerson() throws IOException {
        String terraform = Files.readString(ALARMS, StandardCharsets.UTF_8);
        String rules = Files.readString(RULES, StandardCharsets.UTF_8);

        Matcher resource = RESOURCE.matcher(terraform);
        int stalled = 0;
        while (resource.find()) {
            if (resource.group(1).startsWith("real_risk_job_stalled")) {
                stalled++;
            }
        }
        assertThat(stalled).isEqualTo(3);
        assertThat(terraform.split("treat_missing_data\\s*=\\s*\"breaching\"", -1))
                .hasSize(4);
        assertThat(rules).doesNotContain("mint=").doesNotContain("user=").doesNotContain("tenant=");
        assertThat(terraform).doesNotContainPattern("dimensions\\s*=\\s*\\{[^}]*(mint|user|tenant|address)");
    }
}
