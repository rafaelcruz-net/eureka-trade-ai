package com.memetrade.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.memetrade.botwallet.domain.Network;
import com.memetrade.botwallet.infra.RealProperties;
import com.memetrade.botwallet.infra.RealTestSeeder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 261121-paper-real-unico U1, Step 15 (BR10.2, NFR3.8; security-design.md "Semente da liberação de teste";
 * infrastructure-specification.md "Chave da semente de teste"): the seed that writes an ACTIVE REAL release without
 * the 24 hours of quarantine can never be turned on by a production configuration. Read from the versioned files:
 *
 * <ol>
 *   <li>{@code RealTestSeeder} has exactly {@code @Profile("test")}: no negation, no other profile, no expression;
 *   <li>{@code application.yml} has the literal {@code seed-test-release: false}, without a {@code ${...}};
 *   <li>no other {@code application-*.yml} declares the key nor activates the profile {@code test};
 *   <li>the Terraform never sets the profile {@code test} nor names the key;
 *   <li>the {@code docker-compose.yml} of the root never names the key;
 *   <li>no Dockerfile sets the profile {@code test} nor names the key;
 *   <li>no workflow of {@code .github/workflows/} names the key;
 *   <li>the {@code .env.example} never names the key (not even in a comment) and leaves the profile empty;
 *   <li>the only versioned compose that turns the seed on is {@code infra/docker-compose.real.yml}.
 * </ol>
 *
 * Plus a context check: with the default profile and the key on, no {@code RealTestSeeder} bean exists.
 */
class RealSeedProfileGuardTest {

    private static final String KEY = "MEMETRADE_REAL_SEED_TEST_RELEASE";
    private static final String PROPERTY = "seed-test-release";
    private static final Path ROOT = Path.of("..", "..");
    private static final Pattern TEST_PROFILE = Pattern.compile("\\btest\\b");

    @Test
    void theSeederHasExactlyTheTestProfile() {
        Profile profile = RealTestSeeder.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("test");
    }

    @Test
    void theSharedConfigurationHasTheLiteralFalse() throws IOException {
        String yml = read(Path.of("src", "main", "resources", "application.yml"));

        assertThat(yml).containsPattern("(?m)^\\s+" + PROPERTY + ": false\\s*(#.*)?$");
        assertThat(yml).doesNotContainPattern(PROPERTY + ":\\s*\\$\\{");
    }

    @Test
    void noOtherProfileFileDeclaresTheKeyNorActivatesTheTestProfile() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src", "main", "resources"))) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("application-.+\\.yml"))
                    .filter(p -> !p.getFileName().toString().equals("application-test.yml"))
                    .toList()) {
                String yml = read(file);
                assertThat(yml).as(file.toString()).doesNotContain(PROPERTY);
                for (String line : yml.lines().toList()) {
                    if (line.matches("\\s*(active|include|group|spring\\.profiles\\.\\w+)\\s*:.*")) {
                        assertThat(TEST_PROFILE.matcher(line.split(":", 2)[1]).find())
                                .as(file + ": " + line)
                                .isFalse();
                    }
                }
            }
        }
    }

    @Test
    void theTerraformNeverSetsTheTestProfileNorNamesTheKey() throws IOException {
        List<Path> files = tree(ROOT.resolve("infra/terraform"), ".tf");
        files.addAll(tree(ROOT.resolve("infra/terraform/environments"), ".tfvars.example"));
        assertThat(files).isNotEmpty();
        for (Path file : files) {
            String text = read(file);
            assertThat(text).as(file.toString()).doesNotContain(KEY);
            for (String line : text.lines().toList()) {
                if (line.contains("SPRING_PROFILES_ACTIVE") && line.contains("=")) {
                    assertThat(TEST_PROFILE.matcher(line.split("=", 2)[1]).find())
                            .as(file + ": " + line)
                            .isFalse();
                }
            }
        }
    }

    @Test
    void theComposeOfTheRootNeverNamesTheKey() throws IOException {
        assertThat(read(ROOT.resolve("docker-compose.yml"))).doesNotContain(KEY);
    }

    @Test
    void noDockerfileSetsTheTestProfileNorNamesTheKey() throws IOException {
        for (String name : List.of(
                "apps/api/Dockerfile",
                "apps/web/Dockerfile",
                "apps/admin/Dockerfile",
                "apps/txsigner/Dockerfile",
                "infra/kms-sim/Dockerfile")) {
            String text = read(ROOT.resolve(name));
            assertThat(text).as(name).doesNotContain(KEY);
            for (String line : text.lines().toList()) {
                if (line.contains("SPRING_PROFILES_ACTIVE") || line.contains("spring.profiles.active")) {
                    assertThat(TEST_PROFILE.matcher(line).find())
                            .as(name + ": " + line)
                            .isFalse();
                }
            }
        }
    }

    @Test
    void noWorkflowNamesTheKey() throws IOException {
        List<Path> workflows = tree(ROOT.resolve(".github/workflows"), ".yml");
        assertThat(workflows).isNotEmpty();
        for (Path workflow : workflows) {
            assertThat(read(workflow)).as(workflow.toString()).doesNotContain(KEY);
        }
    }

    @Test
    void theEnvExampleNeverNamesTheKeyAndLeavesTheProfileEmpty() throws IOException {
        String example = read(ROOT.resolve(".env.example"));

        assertThat(example).doesNotContain(KEY);
        for (String line : example.lines().toList()) {
            if (line.contains("SPRING_PROFILES_ACTIVE")) {
                assertThat(line).matches("^#\\s*SPRING_PROFILES_ACTIVE=\\s*(#.*)?$");
            }
        }
    }

    @Test
    void theOnlyComposeThatTurnsTheSeedOnIsTheRealOverlayOfTheLocalRun() throws IOException {
        List<Path> composes = tree(ROOT.resolve("infra"), ".yml").stream()
                .filter(p -> p.getFileName().toString().startsWith("docker-compose"))
                .toList();
        assertThat(composes).isNotEmpty();
        for (Path compose : composes) {
            boolean names = read(compose).contains(KEY);
            assertThat(names)
                    .as(compose.toString())
                    .isEqualTo(compose.getFileName().toString().equals("docker-compose.real.yml"));
        }
    }

    @Test
    void withTheDefaultProfileAndTheKeyOnNoSeederBeanExists() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withPropertyValues("memetrade.real.seed-test-release=true")
                .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(RealProperties.class, RealSeedProfileGuardTest::seedOn)
                .withUserConfiguration(RealTestSeeder.class);

        runner.run(context -> assertThat(context).doesNotHaveBean(RealTestSeeder.class));
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .run(context -> assertThat(context).hasSingleBean(RealTestSeeder.class));
    }

    private static RealProperties seedOn() {
        return new RealProperties(
                false, "v3", Network.LOCALNET, 1L, 1L, 1L, 1L, 1L, 1, 0L, true, null, null, null, null, null);
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
