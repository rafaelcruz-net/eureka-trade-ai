package com.memetrade.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.ledger.infra.LedgerProperties;
import com.memetrade.shared.config.RuntimeSettingsGuard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/** Passo 1 of U7 (NFR3.5, NFR10.8): the scheduler pool reaches 11 and the ledger settings are fixed. */
@Tag("u7")
class U7SettingsTest {

    @Test
    void theGuardRefusesTenSchedulerThreadsAndAcceptsEleven() {
        assertThatThrownBy(() -> new RuntimeSettingsGuard(environment("10")))
                .hasMessageContaining("SCHEDULER_POOL_SIZE")
                .hasMessageContaining("between 11 and 32");
        assertThatCode(() -> new RuntimeSettingsGuard(environment("11"))).doesNotThrowAnyException();
        assertThatCode(() -> new RuntimeSettingsGuard(environment("32"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> new RuntimeSettingsGuard(environment("33")))
                .hasMessageContaining("SCHEDULER_POOL_SIZE");
    }

    @Test
    void applicationYmlShipsElevenSchedulerThreadsAndTheFixedLedgerValues() throws IOException {
        PropertySource<?> yml = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .getFirst();

        assertThat(String.valueOf(yml.getProperty("spring.task.scheduling.pool.size")))
                .isEqualTo("${SCHEDULER_POOL_SIZE:11}");
        assertThat(String.valueOf(yml.getProperty("memetrade.ledger.marking-fallback-interval")))
                .isEqualTo("60s");
        assertThat(String.valueOf(yml.getProperty("memetrade.ledger.reconciliation-interval")))
                .isEqualTo("5m");
        assertThat(String.valueOf(yml.getProperty("memetrade.ledger.reconciliation-run-limit-per-minute")))
                .isEqualTo("6");
        assertThat(String.valueOf(yml.getProperty("memetrade.ledger.lock-timeout")))
                .isEqualTo("2s");
        assertThat(String.valueOf(yml.getProperty("memetrade.ledger.statement-timeout")))
                .isEqualTo("5s");
        assertThat(String.valueOf(yml.getProperty("memetrade.ledger.job-lock-timeout")))
                .isEqualTo("1s");
        // Fixed values: none of them reads an environment variable.
        assertThat(String.valueOf(yml.getProperty("memetrade.ledger.lock-timeout")))
                .doesNotContain("${");
    }

    @Test
    void theComposeFileAndTheEnvExampleCarryTheNewDefault() throws IOException {
        String compose = Files.readString(Path.of("..", "..", "docker-compose.yml"), StandardCharsets.UTF_8);
        String example = Files.readString(Path.of("..", "..", ".env.example"), StandardCharsets.UTF_8);

        assertThat(compose).contains("SCHEDULER_POOL_SIZE: ${SCHEDULER_POOL_SIZE:-11}");
        assertThat(example).contains("Scheduler threads, 11 to 32 [11]");
    }

    @Test
    void theLedgerPropertiesRefuseNonPositiveValues() {
        LedgerProperties defaults = LedgerProperties.defaults();
        assertThat(defaults.lockTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(defaults.reconciliationRunLimitPerMinute()).isEqualTo(6);
        assertThatThrownBy(() -> new LedgerProperties(
                        Duration.ofSeconds(60),
                        Duration.ofMinutes(5),
                        0,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(1)))
                .hasMessageContaining("reconciliation-run-limit-per-minute");
        assertThatThrownBy(() -> new LedgerProperties(
                        Duration.ofSeconds(60),
                        Duration.ofMinutes(5),
                        6,
                        Duration.ZERO,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(1)))
                .hasMessageContaining("lock-timeout");
    }

    private static MockEnvironment environment(String scheduler) {
        return new MockEnvironment()
                .withProperty("spring.datasource.hikari.maximum-pool-size", "20")
                .withProperty("spring.task.scheduling.pool.size", scheduler);
    }
}
