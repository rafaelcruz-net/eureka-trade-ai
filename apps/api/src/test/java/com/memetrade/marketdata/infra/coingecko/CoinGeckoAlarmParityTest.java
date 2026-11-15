package com.memetrade.marketdata.infra.coingecko;

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
 * Step 13 (monitoring-design.md Q4, infrastructure-specification.md): the alert rules of the real market source in
 * {@code infra/observability/marketdata-alerts.yml} (Prometheus, promtool) and the CloudWatch alarms in
 * {@code infra/terraform/modules/observability/marketdata.tf} have the same names, thresholds and windows. Also checks
 * that {@code .env.example} names {@code COINGECKO_API_KEY} without a value and that the local compositions keep the
 * simulated sources.
 */
class CoinGeckoAlarmParityTest {

    private static final Path ROOT = Path.of("..", "..");
    private static final Path RULES = ROOT.resolve("infra/observability/marketdata-alerts.yml");
    private static final Path ALARMS = ROOT.resolve("infra/terraform/modules/observability/marketdata.tf");

    private static final Pattern RESOURCE = Pattern.compile("resource \"aws_cloudwatch_metric_alarm\" \"(\\w+)\" \\{");
    private static final Pattern NAME = Pattern.compile("alarm_name\\s*=\\s*\"\\$\\{var\\.name}-([a-z0-9-]+)\"");
    private static final Pattern THRESHOLD = Pattern.compile("\\n\\s*threshold\\s*=\\s*([0-9.]+)");
    private static final Pattern PERIOD = Pattern.compile("\\n\\s*period\\s*=\\s*(\\d+)");
    private static final Pattern EVALUATIONS = Pattern.compile("evaluation_periods\\s*=\\s*(\\d+)");

    record Rule(String name, String expr, String forDuration, BigDecimal threshold, long windowSeconds) {}

    record Alarm(String name, BigDecimal threshold, long windowSeconds) {}

    @SuppressWarnings("unchecked")
    static List<Rule> rules() throws IOException {
        Map<String, Object> document = new Yaml().load(Files.readString(RULES, StandardCharsets.UTF_8));
        List<Rule> rules = new ArrayList<>();
        for (Map<String, Object> group : (List<Map<String, Object>>) document.get("groups")) {
            for (Map<String, Object> rule : (List<Map<String, Object>>) group.get("rules")) {
                if (!rule.containsKey("alert")) {
                    continue;
                }
                Map<String, Object> annotations = (Map<String, Object>) rule.get("annotations");
                rules.add(new Rule(
                        kebab(String.valueOf(rule.get("alert"))),
                        String.valueOf(rule.get("expr")),
                        rule.containsKey("for") ? String.valueOf(rule.get("for")) : null,
                        new BigDecimal(String.valueOf(annotations.get("threshold"))),
                        Long.parseLong(String.valueOf(annotations.get("window_seconds")))));
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
            alarms.put(name, new Alarm(name, new BigDecimal(group(THRESHOLD, block)), period * evaluations));
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
    void everyRuleHasAnAlarmWithTheSameNameThresholdAndWindowAndViceVersa() throws IOException {
        List<Rule> rules = rules();
        Map<String, Alarm> alarms = alarms();

        assertThat(rules).hasSize(6);
        assertThat(alarms.keySet())
                .containsExactlyInAnyOrderElementsOf(
                        rules.stream().map(Rule::name).toList());
        for (Rule rule : rules) {
            Alarm alarm = alarms.get(rule.name());
            assertThat(alarm.threshold()).as(rule.name()).isEqualByComparingTo(rule.threshold());
            assertThat(alarm.windowSeconds()).as(rule.name()).isEqualTo(rule.windowSeconds());
        }
    }

    @Test
    void eachRuleExpressionUsesItsOwnThresholdAndWindow() throws IOException {
        for (Rule rule : rules()) {
            assertThat(rule.expr()).as(rule.name()).contains(rule.threshold().toPlainString());
            if (rule.forDuration() != null) {
                assertThat(seconds(rule.forDuration())).as(rule.name()).isEqualTo(rule.windowSeconds());
            } else if (rule.expr().contains("[")) {
                assertThat(rule.expr()).as(rule.name()).contains("[" + range(rule.windowSeconds()) + "]");
            }
        }
    }

    @Test
    void theSloRecordingRuleAndTheLabelsNeverCarryAKeyOrAMint() throws IOException {
        String rules = Files.readString(RULES, StandardCharsets.UTF_8);

        assertThat(rules).contains("record: marketdata_price_fresh_minute");
        assertThat(rules).doesNotContain("mint=").doesNotContain("api_key").doesNotContain("COINGECKO_API_KEY=");
    }

    @Test
    void theKeyIsNamedWithoutAValueAndTheLocalCompositionsKeepTheSimulatedSources() throws IOException {
        String example = Files.readString(ROOT.resolve(".env.example"), StandardCharsets.UTF_8);
        String compose = Files.readString(ROOT.resolve("docker-compose.yml"), StandardCharsets.UTF_8);

        assertThat(example).containsPattern("(?m)^# COINGECKO_API_KEY=$");
        assertThat(compose).doesNotContain("COINGECKO_API_KEY");
        assertThat(compose).contains("MARKET_SOURCES: ${MARKET_SOURCES:-sim-a,sim-b}");
    }

    private static long seconds(String duration) {
        long value = Long.parseLong(duration.substring(0, duration.length() - 1));
        return switch (duration.charAt(duration.length() - 1)) {
            case 's' -> value;
            case 'm' -> value * 60;
            case 'h' -> value * 3600;
            default -> throw new IllegalArgumentException(duration);
        };
    }

    private static String range(long seconds) {
        return seconds % 3600 == 0 ? (seconds / 3600) + "h" : seconds + "s";
    }
}
