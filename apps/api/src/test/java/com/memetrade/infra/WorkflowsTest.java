package com.memetrade.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** CI integrity (team.md): third-party actions pinned by commit SHA, minimal permissions, no pull_request_target. */
@Tag("u1")
class WorkflowsTest {

    private static final Path WORKFLOWS = Path.of("..", "..", ".github", "workflows");
    private static final Pattern USES = Pattern.compile("(?m)^\\s*-?\\s*uses:\\s*(\\S+)");

    @Test
    void everyThirdPartyActionIsPinnedByAFullCommitSha() throws IOException {
        List<String> unpinned = new ArrayList<>();
        for (Path workflow : workflows()) {
            unpinned.addAll(unpinnedActions(Files.readString(workflow, StandardCharsets.UTF_8)));
        }
        assertThat(unpinned).isEmpty();
    }

    @Test
    void everyWorkflowDeclaresReadOnlyPermissionsAndNeverUsesPullRequestTarget() throws IOException {
        for (Path workflow : workflows()) {
            String text = Files.readString(workflow, StandardCharsets.UTF_8);
            Map<String, Object> yaml = new Yaml().load(text);
            assertThat(yaml).as(workflow.toString()).containsKey("permissions");
            assertThat(String.valueOf(yaml.get("permissions")))
                    .as(workflow.toString())
                    .isEqualTo("{contents=read}");
            assertThat(text)
                    .as(workflow.toString())
                    .doesNotContain("pull_request_target")
                    .doesNotContainPattern(":\\s*write\\b");
        }
    }

    @Test
    void theCheckRejectsTagsAndBranches() {
        String sample = """
                steps:
                  - uses: actions/checkout@v4
                  - uses: actions/setup-node@main
                  - uses: actions/cache@5a3ec84eff668545956fd18022155c47e93e2684 # v4.2.3
                  - uses: ./local-action
                """;

        assertThat(unpinnedActions(sample)).containsExactly("actions/checkout@v4", "actions/setup-node@main");
    }

    /** U2 (NFR6.3): the backend job resolves both compositions before the tests, with placeholders only. */
    @Test
    @Tag("u2")
    @SuppressWarnings("unchecked")
    void theBackendJobChecksTheConfigOfBothCompositions() throws IOException {
        Map<String, Object> ci = new Yaml().load(Files.readString(WORKFLOWS.resolve("ci.yml"), StandardCharsets.UTF_8));
        Map<String, Object> backend = (Map<String, Object>) ((Map<String, Object>) ci.get("jobs")).get("backend");
        List<Map<String, Object>> steps = (List<Map<String, Object>>) backend.get("steps");
        Map<String, Object> composeConfig = steps.stream()
                .filter(step -> "Compose config".equals(step.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ci.yml backend job has no 'Compose config' step"));

        assertThat(String.valueOf(composeConfig.get("run")))
                .contains("docker compose -f docker-compose.yml config --quiet")
                .contains("docker compose -f docker-compose.yml -f infra/docker-compose.test.yml config --quiet");
        assertThat(((Map<String, Object>) composeConfig.get("env")).values())
                .allMatch(value -> String.valueOf(value).startsWith("ci"));
        assertThat(steps.indexOf(composeConfig))
                .isLessThan(steps.indexOf(steps.stream()
                        .filter(step -> "Verify backend".equals(step.get("name")))
                        .findFirst()
                        .orElseThrow()));
    }

    /** U2 (C8 change): the contract job compares the guest and the administrative specifications. */
    @Test
    @Tag("u2")
    void theContractJobComparesTheAdministrativeSpecificationToo() throws IOException {
        assertThat(Files.readString(WORKFLOWS.resolve("ci.yml"), StandardCharsets.UTF_8))
                .contains("-Dtest=GuestOpenApiContractTest,AdminOpenApiContractTest");
    }

    /**
     * U3 (Infrastructure Design review finding, Passo 21): the manual load workflow offers the u3 scenario and passes
     * the invitation lookup limit next to the login limit, only to the loadtest profile of this job.
     */
    @Test
    @Tag("u3")
    @SuppressWarnings("unchecked")
    void theLoadWorkflowOffersTheU3ScenarioAndRaisesTheLookupLimitOnlyThere() throws IOException {
        String text = Files.readString(WORKFLOWS.resolve("load-test.yml"), StandardCharsets.UTF_8);
        Map<String, Object> workflow = new Yaml().load(text);
        // YAML 1.1 (SnakeYAML) reads the key "on" as the boolean true.
        Object dispatch = workflow.containsKey("on") ? workflow.get("on") : ((Map<?, ?>) workflow).get(Boolean.TRUE);

        assertThat(String.valueOf(dispatch)).contains("u3").contains("u1").contains("u4");
        Map<String, Object> jobs = (Map<String, Object>) workflow.get("jobs");
        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) ((Map<String, Object>) jobs.get("load")).get("steps");
        Map<String, Object> run = steps.stream()
                .filter(step -> String.valueOf(step.get("run")).contains("scripts/load-test.sh"))
                .findFirst()
                .orElseThrow();
        Map<String, Object> env = (Map<String, Object>) run.get("env");
        assertThat(env)
                .containsEntry("SPRING_PROFILES_ACTIVE", "test,loadtest")
                .containsKeys("LOGIN_RATE_LIMIT_PER_MINUTE", "INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE", "LOAD_SCENARIO");
        for (Path other : workflows()) {
            if (!other.getFileName().toString().equals("load-test.yml")) {
                assertThat(Files.readString(other, StandardCharsets.UTF_8))
                        .as(other.toString())
                        .doesNotContain("INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE")
                        .doesNotContain("loadtest");
            }
        }
    }

    /** U7 (Passo 23, cicd-pipeline.md): the manual load workflow offers u7 and u7-jobs, run by scripts/load-test.sh. */
    @Test
    @Tag("u7")
    void theLoadWorkflowAndTheScriptOfferTheU7Scenarios() throws IOException {
        String workflow = Files.readString(WORKFLOWS.resolve("load-test.yml"), StandardCharsets.UTF_8);
        String script = Files.readString(Path.of("..", "..", "scripts", "load-test.sh"), StandardCharsets.UTF_8);
        String runner =
                Files.readString(Path.of("..", "..", "scripts", "load", "load-test.mjs"), StandardCharsets.UTF_8);

        assertThat(workflow).contains("- u7\n").contains("- u7-jobs\n");
        assertThat(unpinnedActions(workflow)).isEmpty();
        assertThat(script)
                .contains("  u7)")
                .contains("  u7-jobs)")
                .contains("LOADTEST_CYCLE_BOTS")
                .contains("LOADTEST_FILLS_PER_PERSON:-10000")
                .contains("DURATION_S:-600")
                .contains("DURATION_S:-1800")
                .as("one invitation per person besides the smoke account")
                .contains("Number(process.env.LOADTEST_LEDGER_PEOPLE)-1");
        assertThat(runner)
                .contains("loadtest_paper_execute_seconds")
                .contains("Seq Scan on (ledger_entry|fill)")
                .contains("execute: 0.25")
                .contains("ms > 10_000")
                .contains("components?.loadtestLedger")
                .contains("docs/perf/u7-ledger-pnl-${report.date}.md")
                .as("the scenarios run since the load cycle driver exists (Passo 23, 2026-11-04)")
                .doesNotContain("cycle principal of C10 (U5)");
        // Review repair of the U7 code generation (2026-11-04): closePositions is measured in u7 (R-04), the
        // reconciliation rounds and "check now" are bounded (R-01), and only PostgreSQL in u7 has the 1 GB memory
        // guard decided by the project owner (R-02); every other container and scenario keeps 900 MB.
        assertThat(script).contains("LOADTEST_CLOSE_BOTS=\"${LOADTEST_CLOSE_BOTS:-10}\"");
        assertThat(runner)
                .contains("const MEMORY_LIMIT_MB = 900;")
                .contains("const U7_POSTGRES_MEMORY_LIMIT_MB = 1024;")
                .contains(
                        "SCENARIO === 'u7' && container === 'postgres' ? U7_POSTGRES_MEMORY_LIMIT_MB : MEMORY_LIMIT_MB")
                .contains("loadtest_paper_close_seconds")
                .contains("close: 0.25")
                .contains("checkNow: 5.0")
                .contains("const RECONCILIATION_ROUND_LIMIT_MS = 60_000;");
        for (Path other : workflows()) {
            if (!other.getFileName().toString().equals("load-test.yml")) {
                assertThat(Files.readString(other, StandardCharsets.UTF_8))
                        .as(other.toString())
                        .doesNotContain("LOADTEST_");
            }
        }
    }

    /**
     * U6 (Passo 16, cicd-pipeline.md): the manual load workflow offers u6, run by scripts/load-test.sh over the ledger
     * load (people from LOADTEST_LEDGER_PEOPLE, no seeded fills, 60 bots, invitations as in u7), with its windows,
     * limits, plans and report in scripts/load/load-test.mjs; the actions stay pinned by SHA.
     */
    @Test
    @Tag("u6")
    void theLoadWorkflowAndTheScriptOfferTheU6Scenario() throws IOException {
        String workflow = Files.readString(WORKFLOWS.resolve("load-test.yml"), StandardCharsets.UTF_8);
        String script = Files.readString(Path.of("..", "..", "scripts", "load-test.sh"), StandardCharsets.UTF_8);
        String runner =
                Files.readString(Path.of("..", "..", "scripts", "load", "load-test.mjs"), StandardCharsets.UTF_8);

        assertThat(workflow).contains("- u6\n");
        assertThat(unpinnedActions(workflow)).isEmpty();
        assertThat(script)
                .contains("  u6)")
                .contains("LOADTEST_LEDGER_PEOPLE=\"${LOADTEST_LEDGER_PEOPLE:-20}\" LOADTEST_FILLS_PER_PERSON=0")
                .contains("LOADTEST_CYCLE_BOTS=60")
                .contains("LOADTEST_U6_ENABLED=true")
                .contains("u7 | u7-jobs | u6)")
                .doesNotContain("LOADTEST_U6_PEOPLE");
        assertThat(runner)
                .contains("loadtest_risk_evaluate_seconds")
                .contains("components?.loadtestRisk")
                .contains("Seq Scan on (order_evaluation|order_rejection)")
                .contains("evaluateS: 0.25")
                .contains("rejectionsS: 1.0")
                .contains("batchMs: 100")
                .contains("withPurgeRatio: 1.1")
                .contains("docs/perf/u6-risk-control-${report.date}.md");
    }

    /**
     * U5 (Passo 20, cicd-pipeline.md): the manual load workflow offers u5, run by scripts/load-test.sh with the worker
     * (actuator on loopback 8082, docker stats, the stopwatch lines), the bot seed and the frequent-signal scenario;
     * the ledger load of U7 stays at 0 and the actions stay pinned by SHA.
     */
    @Test
    @Tag("u5")
    void theLoadWorkflowAndTheScriptOfferTheU5Scenario() throws IOException {
        String workflow = Files.readString(WORKFLOWS.resolve("load-test.yml"), StandardCharsets.UTF_8);
        String script = Files.readString(Path.of("..", "..", "scripts", "load-test.sh"), StandardCharsets.UTF_8);
        String runner =
                Files.readString(Path.of("..", "..", "scripts", "load", "load-test.mjs"), StandardCharsets.UTF_8);

        assertThat(workflow).contains("- u5\n");
        assertThat(unpinnedActions(workflow)).isEmpty();
        assertThat(script)
                .contains("  u5)")
                .contains("DURATION_S=\"${DURATION_S:-1800}\"")
                .contains("LOADTEST_U5_ENABLED=true")
                .contains("MARKET_SIMULATED_SCENARIO=FREQUENT_SIGNALS")
                .contains("export LOADTEST_LEDGER_PEOPLE=0 LOADTEST_FILLS_PER_PERSON=0 LOADTEST_CYCLE_BOTS=0")
                .contains("WORKER_CONTAINER=\"$WORKER_CONTAINER\"")
                .contains("WORKER_MANAGEMENT_URL=\"http://127.0.0.1:8082\"");
        assertThat(runner)
                .contains("components?.loadtestBots")
                .contains("loadtest_bots_cycle_seconds")
                .contains("loadtest_bots_gate_seconds")
                .contains("Loadtest cycle timing")
                .contains("cycleS: 2.0")
                .contains("gateS: 0.5")
                .contains("routesS: 1.0")
                .contains("screenS: 2.0")
                .contains("liveS: 4.0")
                .contains("maxConnections: 14")
                .contains("/api/v1/stream/dashboard")
                .contains("docs/perf/u5-bots-decision-${report.date}.md");
    }

    static List<String> unpinnedActions(String workflow) {
        List<String> unpinned = new ArrayList<>();
        Matcher matcher = USES.matcher(workflow);
        while (matcher.find()) {
            String action = matcher.group(1);
            if (!action.startsWith("./") && !action.matches("[^@]+@[0-9a-f]{40}")) {
                unpinned.add(action);
            }
        }
        return unpinned;
    }

    /**
     * U8 (R-03 of the Infrastructure Design): the administrator app has its own lockfile, so the dependency audit and
     * the automatic update PRs cover it next to apps/web, with the same wait for new npm versions.
     */
    @Test
    @Tag("u8")
    @SuppressWarnings("unchecked")
    void theAdministratorAppIsAuditedAndUpdatedLikeTheGuestApp() throws IOException {
        assertThat(Files.readString(WORKFLOWS.resolve("security.yml"), StandardCharsets.UTF_8))
                .contains("pnpm --dir apps/web audit --audit-level high")
                .contains("pnpm --dir apps/admin audit --audit-level high");

        Map<String, Object> dependabot = new Yaml()
                .load(Files.readString(WORKFLOWS.getParent().resolve("dependabot.yml"), StandardCharsets.UTF_8));
        List<Map<String, Object>> updates = (List<Map<String, Object>>) dependabot.get("updates");
        Map<String, Object> adminNpm = updates.stream()
                .filter(u -> "npm".equals(u.get("package-ecosystem")) && "/apps/admin".equals(u.get("directory")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("dependabot.yml has no npm entry for /apps/admin"));
        assertThat(((Map<String, Object>) adminNpm.get("cooldown")).get("default-days"))
                .isEqualTo(5);
        Map<String, Object> docker = updates.stream()
                .filter(u -> "docker".equals(u.get("package-ecosystem")))
                .findFirst()
                .orElseThrow();
        assertThat((List<String>) docker.get("directories")).contains("/apps/admin");
    }

    private static List<Path> workflows() throws IOException {
        try (Stream<Path> files = Files.list(WORKFLOWS)) {
            List<Path> found = files.filter(p -> p.toString().endsWith(".yml")).toList();
            assertThat(found).isNotEmpty();
            return found;
        }
    }
}
