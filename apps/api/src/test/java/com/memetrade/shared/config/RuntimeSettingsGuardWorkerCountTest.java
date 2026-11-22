package com.memetrade.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 261113-trading-real-bot U4 (U4-NFR3.7 corrected by logical-components.md "Pool do worker"): the scheduler pool of the
 * worker has one thread per scheduled method of the worker. The count test fails when the worker gets more scheduled
 * methods than the minimum of the guard; the defaults of {@code application-worker.yml} pass the guard.
 */
@Tag("u4-real-execution")
class RuntimeSettingsGuardWorkerCountTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memetrade");

    /**
     * Every {@code @Scheduled} method of a class that exists only in the worker ({@code @ProcessRole(WORKER)}) and in
     * every profile: a class with exactly {@code @Profile("test")} never exists in production
     * ({@code RecordedSwapProfileGuardTest}) and is counted by {@link #testOnlyWorkerScheduledMethods()}.
     */
    static List<String> workerScheduledMethods() {
        return scheduledWorkerMethods(false);
    }

    /** The {@code @Scheduled} methods of the worker that exist only in the profile {@code test}. */
    static List<String> testOnlyWorkerScheduledMethods() {
        return scheduledWorkerMethods(true);
    }

    private static List<String> scheduledWorkerMethods(boolean testOnly) {
        List<String> methods = new ArrayList<>();
        for (JavaClass type : PRODUCTION) {
            if (!type.isAnnotatedWith(ProcessRole.class)) {
                continue;
            }
            ProcessRole role = type.getAnnotationOfType(ProcessRole.class);
            if (role.value() != ProcessRole.Kind.WORKER) {
                continue;
            }
            boolean onlyInTest = type.isAnnotatedWith(Profile.class)
                    && Arrays.equals(type.getAnnotationOfType(Profile.class).value(), new String[] {"test"});
            if (onlyInTest != testOnly) {
                continue;
            }
            for (JavaMethod method : type.getMethods()) {
                if (method.isAnnotatedWith(Scheduled.class)) {
                    methods.add(type.getSimpleName() + "." + method.getName());
                }
            }
        }
        return methods;
    }

    @Test
    void theWorkerHasNoMoreScheduledMethodsThanTheSmallestPoolOfTheGuard() {
        List<String> scheduled = workerScheduledMethods();

        assertThat(scheduled.size())
                .as("scheduled methods of the worker: %s", scheduled)
                .isLessThanOrEqualTo(RuntimeSettingsGuard.MIN_WORKER_SCHEDULER_POOL);
        assertThat(RuntimeSettingsGuard.MIN_WORKER_POOL)
                .as("4 cycles + 4 reserve + one connection per scheduled method")
                .isGreaterThanOrEqualTo(8 + scheduled.size());
    }

    /**
     * 261121-paper-real-unico U1 (review R-01): the worker of the REAL Compose (the only run with the profile
     * {@code test} and RECORDED_MINT) also has one scheduler thread and one connection per scheduled method, the
     * test-only ones included, and its pools pass the guard.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theWorkerOfTheRealComposeHasOneThreadPerScheduledMethodTheTestOnlyOnesIncluded() throws IOException {
        int scheduled = workerScheduledMethods().size()
                + testOnlyWorkerScheduledMethods().size();
        Map<String, Object> compose;
        try (InputStream in = Files.newInputStream(Path.of("..", "..", "infra", "docker-compose.real.yml"))) {
            compose = new org.yaml.snakeyaml.Yaml().load(in);
        }
        Map<String, Object> worker =
                (Map<String, Object>) ((Map<String, Object>) compose.get("services")).get("worker");
        Map<String, Object> environment = (Map<String, Object>) worker.get("environment");
        String scheduler = String.valueOf(environment.get("SCHEDULER_POOL_SIZE"));
        String pool = String.valueOf(environment.get("DB_POOL_MAX_SIZE"));

        assertThat(testOnlyWorkerScheduledMethods()).containsExactly("RecordedSwapFirstCycle.tick");
        assertThat(Integer.parseInt(scheduler)).isGreaterThanOrEqualTo(scheduled);
        assertThat(Integer.parseInt(pool)).isGreaterThanOrEqualTo(8 + scheduled);
        assertThatCode(() -> new RuntimeSettingsGuard(environment(pool, scheduler)))
                .doesNotThrowAnyException();
    }

    @Test
    void theWorkerDefaultsPassTheGuard() throws IOException {
        PropertySource<?> worker = new YamlPropertySourceLoader()
                .load("worker", new ClassPathResource("application-worker.yml"))
                .getFirst();
        String pool = resolve(String.valueOf(worker.getProperty("spring.datasource.hikari.maximum-pool-size")));
        String scheduler = resolve(String.valueOf(worker.getProperty("spring.task.scheduling.pool.size")));

        assertThat(pool).isEqualTo("36");
        assertThat(scheduler).isEqualTo("28");
        assertThatCode(() -> new RuntimeSettingsGuard(environment(pool, scheduler)))
                .doesNotThrowAnyException();
    }

    @Test
    void theOldWorkerDefaultsAreRefusedWithTheVariableNamed() {
        // 261113-trading-real-bot U7: the defaults of the U5 (33 and 25) are now refused too.
        assertThatThrownBy(() -> new RuntimeSettingsGuard(environment("33", "28")))
                .hasMessageContaining("DB_POOL_MAX_SIZE")
                .hasMessageContaining("between 36 and 48");
        assertThatThrownBy(() -> new RuntimeSettingsGuard(environment("36", "25")))
                .hasMessageContaining("SCHEDULER_POOL_SIZE")
                .hasMessageContaining("between 28 and 32");
        assertThatThrownBy(() -> new RuntimeSettingsGuard(environment("36", "twenty-five")))
                .hasMessageContaining("SCHEDULER_POOL_SIZE");
    }

    /** The default of a {@code ${VARIABLE:default}} placeholder. */
    private static String resolve(String placeholder) {
        return placeholder.substring(placeholder.indexOf(':') + 1, placeholder.length() - 1);
    }

    private static MockEnvironment environment(String pool, String scheduler) {
        return new MockEnvironment()
                .withProperty(ProcessRole.PROPERTY, "worker")
                .withProperty(RuntimeSettingsGuard.POOL_PROPERTY, pool)
                .withProperty(RuntimeSettingsGuard.SCHEDULER_PROPERTY, scheduler);
    }
}
