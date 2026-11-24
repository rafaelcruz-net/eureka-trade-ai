package com.memetrade.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.bots.infra.JdbcCycleRepository;
import com.memetrade.bots.worker.CycleRunner;
import com.memetrade.bots.worker.OwnHoldingsStep;
import com.memetrade.bots.worker.RealCycle;
import com.memetrade.bots.worker.RecordedSwapFirstCycle;
import com.memetrade.bots.worker.RecordedSwapStrategy;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.config.RecordedSwapFixture;
import com.memetrade.shared.config.RecordedSwapFixtureConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Profile;

/**
 * 261121-paper-real-unico U1 (BR10.1, review R-01; the same guard as {@code RealSeedProfileGuardTest}): the test hooks
 * of the recorded swap (the fixture, the strategy that now also buys once in PAPER, and the first cycle that spares the
 * e2e the 5-minute wait) can never be turned on by a production configuration. They are keyed by ONE variable,
 * {@code RECORDED_MINT} ({@code memetrade.real.test-swap.mint}), and by the profile {@code test}:
 *
 * <ol>
 *   <li>the three classes have exactly {@code @Profile("test")}: no negation, no other profile, no expression; the
 *       strategy and the first cycle exist only in the worker;
 *   <li>among the {@code application*.yml}, only {@code application-test.yml} names the key or the variable;
 *   <li>the Terraform, the {@code docker-compose.yml} of the root, the Dockerfiles, the workflows and the
 *       {@code .env.example} never name the variable;
 *   <li>the only versioned compose that passes it is {@code infra/docker-compose.real.yml};
 *   <li>a context with the variable set but without the profile {@code test} has none of the three beans.
 * </ol>
 */
class RecordedSwapProfileGuardTest {

    private static final String VARIABLE = "RECORDED_MINT";
    private static final String KEY = "test-swap";
    private static final Path ROOT = Path.of("..", "..");

    @Test
    void theThreeHooksHaveExactlyTheTestProfileAndTheTwoCycleHooksLiveInTheWorker() {
        for (Class<?> hook :
                List.of(RecordedSwapFixtureConfig.class, RecordedSwapStrategy.class, RecordedSwapFirstCycle.class)) {
            Profile profile = hook.getAnnotation(Profile.class);
            assertThat(profile).as(hook.getSimpleName()).isNotNull();
            assertThat(profile.value()).as(hook.getSimpleName()).containsExactly("test");
        }
        assertThat(RecordedSwapStrategy.class.getAnnotation(ProcessRole.class).value())
                .isEqualTo(ProcessRole.Kind.WORKER);
        assertThat(RecordedSwapFirstCycle.class.getAnnotation(ProcessRole.class).value())
                .isEqualTo(ProcessRole.Kind.WORKER);
    }

    @Test
    void onlyTheTestProfileFileNamesTheKeyOrTheVariable() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src", "main", "resources"))) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("application(-.+)?\\.yml"))
                    .toList()) {
                String yml = read(file);
                boolean names = yml.contains(VARIABLE) || yml.contains(KEY + ":");
                assertThat(names)
                        .as(file.toString())
                        .isEqualTo(file.getFileName().toString().equals("application-test.yml"));
            }
        }
    }

    @Test
    void noProductionConfigurationNamesTheVariable() throws IOException {
        List<Path> files = tree(ROOT.resolve("infra/terraform"), ".tf");
        files.addAll(tree(ROOT.resolve("infra/terraform/environments"), ".tfvars.example"));
        files.addAll(tree(ROOT.resolve(".github/workflows"), ".yml"));
        files.add(ROOT.resolve("docker-compose.yml"));
        files.add(ROOT.resolve(".env.example"));
        for (String dockerfile : List.of(
                "apps/api/Dockerfile",
                "apps/web/Dockerfile",
                "apps/admin/Dockerfile",
                "apps/txsigner/Dockerfile",
                "infra/kms-sim/Dockerfile")) {
            files.add(ROOT.resolve(dockerfile));
        }
        assertThat(files).hasSizeGreaterThan(7);
        for (Path file : files) {
            assertThat(read(file)).as(file.toString()).doesNotContain(VARIABLE);
        }
    }

    @Test
    void theOnlyComposeThatPassesTheVariableIsTheRealOverlayOfTheLocalRun() throws IOException {
        List<Path> composes = tree(ROOT.resolve("infra"), ".yml").stream()
                .filter(p -> p.getFileName().toString().startsWith("docker-compose"))
                .toList();
        assertThat(composes).isNotEmpty();
        for (Path compose : composes) {
            assertThat(read(compose).contains(VARIABLE))
                    .as(compose.toString())
                    .isEqualTo(compose.getFileName().toString().equals("docker-compose.real.yml"));
        }
    }

    @Test
    void withTheVariableSetButWithoutTheTestProfileNoHookBeanExists() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withPropertyValues(
                        "memetrade.process=worker",
                        "memetrade.bots.scheduler.enabled=true",
                        "memetrade.real.test-swap.mint=DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263",
                        "memetrade.real.test-swap.decimals=5",
                        "memetrade.real.test-swap.buy-lamports=50000000",
                        "memetrade.real.test-swap.expected-out-units=154230000000")
                .withBean(JdbcCycleRepository.class, () -> mock(JdbcCycleRepository.class))
                .withBean(JdbcBotRepository.class, () -> mock(JdbcBotRepository.class))
                .withBean(CycleRunner.class, () -> mock(CycleRunner.class))
                // The mock of the runner still receives its method injection of the REAL path.
                .withBean(RealCycle.class, () -> mock(RealCycle.class))
                .withBean(OwnHoldingsStep.class, () -> mock(OwnHoldingsStep.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withUserConfiguration(
                        RecordedSwapFixtureConfig.class, RecordedSwapStrategy.class, RecordedSwapFirstCycle.class);

        runner.run(context -> {
            assertThat(context).doesNotHaveBean(RecordedSwapFixture.class);
            assertThat(context).doesNotHaveBean(RecordedSwapStrategy.class);
            assertThat(context).doesNotHaveBean(RecordedSwapFirstCycle.class);
        });
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .run(context -> {
                    assertThat(context).hasSingleBean(RecordedSwapFixture.class);
                    assertThat(context).hasSingleBean(RecordedSwapStrategy.class);
                    assertThat(context).hasSingleBean(RecordedSwapFirstCycle.class);
                });
    }

    private static List<Path> tree(Path root, String suffix) throws IOException {
        if (!Files.isDirectory(root)) {
            return new ArrayList<>();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return new ArrayList<>(files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .filter(p -> !p.toString().contains(".terraform"))
                    .toList());
        }
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
