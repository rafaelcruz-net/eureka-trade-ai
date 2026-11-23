package com.memetrade.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.loadtest.LoadtestPnlHealthIndicator;
import com.memetrade.pnl.loadtest.LoadtestPnlSeed;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

/**
 * 261121-paper-real-unico U2, Etapa 25 (NFR1.6; in the pattern of {@code RealSeedProfileGuardTest}): the seed of the
 * scenario {@code pnl-by-mode}, which writes synthetic REAL orders and ledger rows straight in the database, can never be
 * turned on outside the load test:
 *
 * <ol>
 *   <li>the seed and its health component have exactly {@code @Profile("loadtest")} (no negation, no expression), and
 *       the profile {@code loadtest} only starts together with {@code test} ({@code LoadtestProfileGuard});
 *   <li>its variable {@code LOADTEST_PNL_PEOPLE} is read only by {@code application-loadtest.yml} (default 0), passed
 *       only by the test overlay and set only by {@code scripts/load-test.sh}: never by the shared configuration, the
 *       Terraform, a Dockerfile, the compose of the root nor the {@code .env.example}.
 * </ol>
 */
class LoadtestPnlSeedProfileGuardTest {

    private static final String KEY = "pnl:";
    private static final String VARIABLE = "LOADTEST_PNL_PEOPLE";
    private static final Path ROOT = Path.of("..", "..");

    @Test
    void theSeedAndItsHealthHaveExactlyTheLoadtestProfile() {
        for (Class<?> type : List.of(LoadtestPnlSeed.class, LoadtestPnlHealthIndicator.class)) {
            Profile profile = type.getAnnotation(Profile.class);
            assertThat(profile).as(type.getSimpleName()).isNotNull();
            assertThat(profile.value()).as(type.getSimpleName()).containsExactly("loadtest");
        }
    }

    @Test
    void theVariableIsReadOnlyByTheLoadtestProfileAndSetOnlyByTheLoadScript() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> resources = Files.list(Path.of("src", "main", "resources"))) {
            resources
                    .filter(p -> p.getFileName().toString().startsWith("application"))
                    .filter(p -> !p.getFileName().toString().equals("application-loadtest.yml"))
                    .forEach(files::add);
        }
        files.addAll(tree(ROOT.resolve("infra/terraform"), ".tf"));
        files.add(ROOT.resolve("docker-compose.yml"));
        files.add(ROOT.resolve("apps/api/Dockerfile"));
        files.add(ROOT.resolve(".env.example"));
        for (Path file : files) {
            if (!Files.exists(file)) {
                continue;
            }
            assertThat(Files.readString(file, StandardCharsets.UTF_8))
                    .as(file.toString())
                    .doesNotContain(VARIABLE);
        }
        String loadtest = Files.readString(
                Path.of("src", "main", "resources", "application-loadtest.yml"), StandardCharsets.UTF_8);
        assertThat(loadtest).contains(KEY).contains("people: ${" + VARIABLE + ":0}");
        assertThat(Files.readString(ROOT.resolve("infra/docker-compose.test.yml"), StandardCharsets.UTF_8))
                .contains(VARIABLE + ": ${" + VARIABLE + ":-0}");
        assertThat(Files.readString(ROOT.resolve("scripts/load-test.sh"), StandardCharsets.UTF_8))
                .contains(VARIABLE);
    }

    private static List<Path> tree(Path root, String suffix) throws IOException {
        if (!Files.exists(root)) {
            return new ArrayList<>();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(suffix))
                    .filter(p -> !p.toString().contains(".terraform"))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
    }
}
