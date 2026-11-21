package com.memetrade.realexecution;

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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Step 15 of the U4, written after the rules (monitoring-design.md "Paridade", U4-NFR5.9 to U4-NFR5.14, U4-NFR3.5): the
 * alert rules of the REAL execution in {@code infra/observability/real-execution-alerts.yml} (Prometheus, promtool) and
 * the CloudWatch alarms in {@code infra/terraform/modules/observability/real-execution.tf} have the same name,
 * threshold, window and dimension ({@code job} or {@code provider}), rule by rule. The seven stalled-job alarms come
 * from one {@code for_each} over the map {@code real_execution_jobs}. Observability Setup: the slow-execution rule
 * (U4-NFR5.13) has its twin too, on the p95 summary named in {@code cloud_metric} (gap OBS-G2: the agent drops
 * histograms), created only with {@code var.p95_alarms_enabled}; every alarm moved to the namespace Eureka/App.
 */
@Tag("u4-real-execution")
class RealExecutionAlarmParityTest {

    private static final Path ROOT = Path.of("..", "..");
    private static final Path RULES = ROOT.resolve("infra/observability/real-execution-alerts.yml");
    private static final Path ALARMS = ROOT.resolve("infra/terraform/modules/observability/real-execution.tf");

    private static final Pattern RESOURCE = Pattern.compile("resource \"aws_cloudwatch_metric_alarm\" \"(\\w+)\" \\{");
    private static final Pattern NAME = Pattern.compile("alarm_name\\s*=\\s*\"\\$\\{var\\.name}-([a-z0-9-]+)\"");
    private static final Pattern THRESHOLD = Pattern.compile("\\n\\s*threshold\\s*=\\s*([0-9.]+)");
    private static final Pattern PERIOD = Pattern.compile("\\n\\s*period\\s*=\\s*(\\d+)");
    private static final Pattern EVALUATIONS = Pattern.compile("evaluation_periods\\s*=\\s*(\\d+)");
    private static final Pattern DIMENSION =
            Pattern.compile("dimensions\\s*=\\s*\\{\\s*(\\w+)\\s*=\\s*\"([A-Za-z_-]+)\"\\s*}");
    private static final Pattern NAMESPACE = Pattern.compile("real_execution_namespace\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern JOBS = Pattern.compile("real_execution_jobs\\s*=\\s*\\{([^}]*)}");
    private static final Pattern JOB_ENTRY = Pattern.compile("([a-z-]+)\\s*=\\s*(\\d+)");
    private static final String STALLED_PREFIX = "real-execution-job-stalled-";

    record Rule(
            String name,
            String expr,
            String forDuration,
            BigDecimal threshold,
            long windowSeconds,
            String job,
            String provider,
            String cloudMetric) {}

    /** An alarm; {@code dimension} is {@code job=<job>} or {@code provider=<provider>}, or null. */
    record Alarm(String name, BigDecimal threshold, long windowSeconds, String dimension) {}

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
                        annotations.containsKey("provider") ? String.valueOf(annotations.get("provider")) : null,
                        annotations.containsKey("cloud_metric")
                                ? String.valueOf(annotations.get("cloud_metric"))
                                : null));
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
            if (block.contains("for_each")) {
                continue;
            }
            String name = group(NAME, block);
            long period = Long.parseLong(group(PERIOD, block));
            long evaluations = Long.parseLong(group(EVALUATIONS, block));
            Matcher dimension = DIMENSION.matcher(block);
            String kept = null;
            if (dimension.find() && COMPARED_DIMENSIONS.contains(dimension.group(1))) {
                kept = dimension.group(1) + "=" + dimension.group(2);
            }
            alarms.put(name, new Alarm(name, new BigDecimal(group(THRESHOLD, block)), period * evaluations, kept));
        }
        Matcher jobs = JOB_ENTRY.matcher(group(JOBS, terraform));
        while (jobs.find()) {
            String name = STALLED_PREFIX + jobs.group(1);
            alarms.put(name, new Alarm(name, BigDecimal.ONE, Long.parseLong(jobs.group(2)), "job=" + jobs.group(1)));
        }
        return alarms;
    }

    /** The dimensions the rules name in their annotations; outcome and code are fixed in the expression. */
    private static final java.util.Set<String> COMPARED_DIMENSIONS = java.util.Set.of("job", "provider");

    private static String group(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        assertThat(matcher.find()).as(pattern.pattern()).isTrue();
        return matcher.group(1);
    }

    static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private static String dimensionOf(Rule rule) {
        if (rule.job() != null) {
            return "job=" + rule.job();
        }
        return rule.provider() == null ? null : "provider=" + rule.provider();
    }

    @Test
    void everyRuleHasAnAlarmWithTheSameNameThresholdWindowAndDimension() throws IOException {
        List<Rule> rules = rules();
        Map<String, Alarm> alarms = alarms();
        String terraform = Files.readString(ALARMS, StandardCharsets.UTF_8);

        assertThat(rules).hasSize(14);
        assertThat(alarms.keySet())
                .containsExactlyInAnyOrderElementsOf(
                        rules.stream().map(Rule::name).toList());
        for (Rule rule : rules) {
            Alarm alarm = alarms.get(rule.name());
            assertThat(alarm.threshold()).as(rule.name()).isEqualByComparingTo(rule.threshold());
            assertThat(alarm.windowSeconds())
                    .as(rule.name())
                    .isEqualTo(com.memetrade.infra.CloudWatchWindows.cloudWindow(
                            rule.windowSeconds(),
                            com.memetrade.infra.CloudWatchWindows.twoReads(terraform, rule.name())));
            assertThat(alarm.dimension()).as(rule.name()).isEqualTo(dimensionOf(rule));
        }
    }

    @Test
    void eachExpressionUsesItsOwnThresholdWindowAndDimension() throws IOException {
        for (Rule rule : rules()) {
            String comparison = rule.job() != null ? "< " : "> ";
            assertThat(rule.expr())
                    .as(rule.name())
                    .contains(comparison + rule.threshold().toPlainString());
            assertThat(rule.windowSeconds() % 60).as(rule.name()).isZero();
            String window = (rule.windowSeconds() / 60) + "m";
            assertThat(rule.expr()).as(rule.name()).contains("[" + window + "]");
            if (rule.forDuration() != null) {
                assertThat(rule.forDuration()).as(rule.name()).isEqualTo(window);
            }
            if (rule.job() != null) {
                assertThat(rule.expr())
                        .as(rule.name())
                        .contains("real_execution_job_runs_total{job=\"" + rule.job() + "\"}")
                        .contains("absent(real_execution_job_runs_total{job=\"" + rule.job() + "\"})");
            }
            if (rule.provider() != null) {
                assertThat(rule.expr()).as(rule.name()).contains("provider=\"" + rule.provider() + "\"");
            }
        }
    }

    @Test
    void theStalledJobsAreTheSevenJobsOfTheWorker() throws IOException {
        assertThat(rules().stream().filter(rule -> rule.job() != null).map(Rule::job))
                .containsExactlyInAnyOrder(
                        "queue", "driver", "confirmation", "ambiguous", "signing-deadline", "health", "liquidation");
        assertThat(rules().stream()
                        .filter(rule -> rule.job() != null)
                        .filter(rule -> rule.windowSeconds() == 60)
                        .map(Rule::job))
                .containsExactlyInAnyOrder("queue", "driver", "confirmation");
    }

    /** U4-NFR5.13: the p95 twin reads the summary of OBS-G2, the worst minute 10 times in a row, and is off by default. */
    @Test
    void theSlowExecutionReadsThePercentileSummaryAndTheAlarmsUseTheNamespaceOfTheAgent() throws IOException {
        String terraform = Files.readString(ALARMS, StandardCharsets.UTF_8);
        assertThat(rules())
                .filteredOn(rule -> rule.cloudMetric() != null)
                .extracting(Rule::name, Rule::cloudMetric)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "real-execution-slow", "real_order_decision_to_send_p95_seconds"));
        String block = terraform.substring(
                terraform.indexOf("resource \"aws_cloudwatch_metric_alarm\" \"real_execution_slow\""));
        assertThat(block)
                .contains("count = var.p95_alarms_enabled ? 1 : 0")
                .contains("metric_name         = \"real_order_decision_to_send_p95_seconds\"")
                .contains("dimensions          = { mode = \"AUTO_FULL\", quantile = \"0.95\" }")
                .contains("statistic           = \"Maximum\"")
                .contains("datapoints_to_alarm = 2");
        assertThat(group(NAMESPACE, terraform)).isEqualTo("Eureka/App");
    }

    @Test
    void theStalledJobsAlarmWhenNothingIsPublishedAndNoLabelCarriesAMintOrAPerson() throws IOException {
        String terraform = Files.readString(ALARMS, StandardCharsets.UTF_8);
        String rules = Files.readString(RULES, StandardCharsets.UTF_8);

        assertThat(terraform.split("treat_missing_data\\s*=\\s*\"breaching\"", -1))
                .as("only the for_each of the stalled jobs treats missing data as breaching")
                .hasSize(2);
        assertThat(rules)
                .doesNotContain("mint=")
                .doesNotContain("user=")
                .doesNotContain("tenant=")
                .doesNotContain("address=")
                .doesNotContain("signature=");
        assertThat(terraform).doesNotContainPattern("dimensions\\s*=\\s*\\{[^}]*(mint|user|tenant|address|signature)");
    }
}
