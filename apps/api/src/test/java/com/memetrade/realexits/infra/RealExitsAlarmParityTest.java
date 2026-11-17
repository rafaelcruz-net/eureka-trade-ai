package com.memetrade.realexits.infra;

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
 * Step 13 of the U7, written after the rules (monitoring-design.md "Paridade", U7-NFR5.7 to U7-NFR5.10): the alert
 * rules of the conditional exits in {@code infra/observability/real-exits-alerts.yml} (Prometheus, promtool) and the
 * CloudWatch alarms in {@code infra/terraform/modules/observability/real-exits.tf} have the same name, threshold, window
 * and job, rule by rule. The four stalled-job alarms come from one {@code for_each} over {@code real_exits_jobs}. Every
 * job of the metrics has its stalled rule.
 */
@Tag("u7-exits-and-fiscal")
class RealExitsAlarmParityTest {

    private static final Path ROOT = Path.of("..", "..");
    private static final Path RULES = ROOT.resolve("infra/observability/real-exits-alerts.yml");
    private static final Path ALARMS = ROOT.resolve("infra/terraform/modules/observability/real-exits.tf");

    private static final Pattern RESOURCE = Pattern.compile("resource \"aws_cloudwatch_metric_alarm\" \"(\\w+)\" \\{");
    private static final Pattern NAME = Pattern.compile("alarm_name\\s*=\\s*\"\\$\\{var\\.name}-([a-z0-9-]+)\"");
    private static final Pattern THRESHOLD = Pattern.compile("\\n\\s*threshold\\s*=\\s*([0-9.]+)");
    private static final Pattern PERIOD = Pattern.compile("\\n\\s*period\\s*=\\s*(\\d+)");
    private static final Pattern EVALUATIONS = Pattern.compile("evaluation_periods\\s*=\\s*(\\d+)");
    private static final Pattern NAMESPACE = Pattern.compile("real_exits_namespace\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern JOBS = Pattern.compile("real_exits_jobs\\s*=\\s*\\{(.*?)\\n  }", Pattern.DOTALL);
    private static final Pattern JOB_ENTRY =
            Pattern.compile("([a-z-]+)\\s*=\\s*\\{\\s*period\\s*=\\s*(\\d+),\\s*evaluations\\s*=\\s*(\\d+)\\s*}");
    private static final String STALLED_PREFIX = "real-exits-job-stalled-";

    record Rule(String name, String expr, BigDecimal threshold, long windowSeconds, String job) {}

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
                        new BigDecimal(String.valueOf(annotations.get("threshold"))),
                        Long.parseLong(String.valueOf(annotations.get("window_seconds"))),
                        annotations.containsKey("job") ? String.valueOf(annotations.get("job")) : null));
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
            alarms.put(name, new Alarm(name, new BigDecimal(group(THRESHOLD, block)), period * evaluations, null));
        }
        Matcher jobs = JOB_ENTRY.matcher(group(JOBS, terraform));
        while (jobs.find()) {
            String name = STALLED_PREFIX + jobs.group(1);
            long window = Long.parseLong(jobs.group(2)) * Long.parseLong(jobs.group(3));
            alarms.put(name, new Alarm(name, BigDecimal.ONE, window, jobs.group(1)));
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
    void everyRuleHasAnAlarmWithTheSameNameThresholdWindowAndJob() throws IOException {
        List<Rule> rules = rules();
        Map<String, Alarm> alarms = alarms();

        assertThat(rules).hasSize(7);
        assertThat(alarms.keySet())
                .containsExactlyInAnyOrderElementsOf(
                        rules.stream().map(Rule::name).toList());
        for (Rule rule : rules) {
            Alarm alarm = alarms.get(rule.name());
            assertThat(alarm.threshold()).as(rule.name()).isEqualByComparingTo(rule.threshold());
            assertThat(alarm.windowSeconds()).as(rule.name()).isEqualTo(rule.windowSeconds());
            assertThat(alarm.job()).as(rule.name()).isEqualTo(rule.job());
        }
    }

    @Test
    void eachExpressionUsesItsOwnThresholdWindowAndJob() throws IOException {
        for (Rule rule : rules()) {
            assertThat(rule.expr())
                    .as(rule.name())
                    .containsPattern("[<>]=? " + Pattern.quote(rule.threshold().toPlainString()));
            assertThat(rule.windowSeconds() % 60).as(rule.name()).isZero();
            assertThat(rule.expr()).as(rule.name()).contains("[" + (rule.windowSeconds() / 60) + "m]");
            if (rule.job() != null) {
                assertThat(rule.expr())
                        .as(rule.name())
                        .contains("real_exits_job_runs_total{job=\"" + rule.job() + "\"}")
                        .contains("absent(real_exits_job_runs_total{job=\"" + rule.job() + "\"})");
            }
        }
    }

    /** U7-NFR5.10: every job of the closed label {@code job} has its stalled rule. */
    @Test
    void everyJobOfTheMetricsHasItsStalledRule() throws IOException {
        assertThat(rules().stream().filter(rule -> rule.job() != null).map(Rule::job))
                .containsExactlyInAnyOrderElementsOf(RealExitsMetrics.JOBS);
    }

    /** U7-NFR5.7 and U7-NFR4.11 share the threshold of 15 minutes. */
    @Test
    void theStalledCancellationUsesTheFifteenMinutesOfTheReadOfThePerson() throws IOException {
        Rule stalled = rules().stream()
                .filter(rule -> rule.name().equals("real-exits-cancelling-stalled"))
                .findFirst()
                .orElseThrow();

        assertThat(stalled.threshold().longValueExact())
                .isEqualTo(com.memetrade.realexits.domain.CancellationWatch.STALLED_AFTER.toSeconds());
    }

    @Test
    void theAlarmsUseTheNamespaceOfTheU7AndNoLabelCarriesAMintOrAPerson() throws IOException {
        String terraform = Files.readString(ALARMS, StandardCharsets.UTF_8);
        String rules = Files.readString(RULES, StandardCharsets.UTF_8);

        assertThat(group(NAMESPACE, terraform)).isEqualTo("Eureka/RealExits");
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
