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

    private static List<Path> workflows() throws IOException {
        try (Stream<Path> files = Files.list(WORKFLOWS)) {
            List<Path> found = files.filter(p -> p.toString().endsWith(".yml")).toList();
            assertThat(found).isNotEmpty();
            return found;
        }
    }
}
