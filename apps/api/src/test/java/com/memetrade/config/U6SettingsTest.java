package com.memetrade.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.riskcontrol.infra.RiskControlProperties;
import com.memetrade.riskcontrol.infra.RiskControlProperties.Purge;
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

/**
 * Passo 1 of U6 (NFR2.5, NFR10.6, BR2.6): the scheduler pool holds the 12 existing scheduled jobs plus the purge (13),
 * and the risk control settings are fixed in {@code application.yml}, never read from the environment.
 */
@Tag("u6")
class U6SettingsTest {

    @Test
    void theGuardRefusesThirteenSchedulerThreadsAndAcceptsFourteen() {
        // U5 (Passo 1): the api has one more scheduled job, the live channel poller.
        assertThat(RuntimeSettingsGuard.MIN_SCHEDULER_POOL).isEqualTo(14);
        assertThatThrownBy(() -> new RuntimeSettingsGuard(environment("13")))
                .hasMessageContaining("SCHEDULER_POOL_SIZE")
                .hasMessageContaining("between 14 and 32");
        assertThatCode(() -> new RuntimeSettingsGuard(environment("14"))).doesNotThrowAnyException();
        assertThatCode(() -> new RuntimeSettingsGuard(environment("32"))).doesNotThrowAnyException();
    }

    @Test
    void applicationYmlShipsThirteenSchedulerThreadsAndTheFixedRiskControlValues() throws IOException {
        PropertySource<?> yml = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .getFirst();

        assertThat(String.valueOf(yml.getProperty("spring.task.scheduling.pool.size")))
                .isEqualTo("${SCHEDULER_POOL_SIZE:14}");
        assertThat(String.valueOf(yml.getProperty("memetrade.riskcontrol.freshness-max-age")))
                .isEqualTo("60s");
        assertThat(String.valueOf(yml.getProperty("memetrade.riskcontrol.purge.cron")))
                .isEqualTo("0 30 3 * * *");
        assertThat(String.valueOf(yml.getProperty("memetrade.riskcontrol.purge.batch-size")))
                .isEqualTo("1000");
        assertThat(String.valueOf(yml.getProperty("memetrade.riskcontrol.purge.max-run")))
                .isEqualTo("30m");
        assertThat(String.valueOf(yml.getProperty("memetrade.riskcontrol.purge.lock-at-most-for")))
                .isEqualTo("45m");
        assertThat(String.valueOf(yml.getProperty("memetrade.riskcontrol.purge.selection-age")))
                .isEqualTo("31d");
        assertThat(String.valueOf(yml.getProperty("memetrade.riskcontrol.purge.trigger-age")))
                .isEqualTo("30d");
        for (String key : new String[] {
            "memetrade.riskcontrol.freshness-max-age",
            "memetrade.riskcontrol.purge.cron",
            "memetrade.riskcontrol.purge.batch-size",
            "memetrade.riskcontrol.purge.selection-age"
        }) {
            assertThat(String.valueOf(yml.getProperty(key))).as(key).doesNotContain("${");
        }
    }

    @Test
    void theComposeFileAndTheEnvExampleCarryThirteenThreadsAndNoRiskVariable() throws IOException {
        String compose = Files.readString(Path.of("..", "..", "docker-compose.yml"), StandardCharsets.UTF_8);
        String example = Files.readString(Path.of("..", "..", ".env.example"), StandardCharsets.UTF_8);

        // U5: 14 in the api since the live channel poller.
        assertThat(compose).contains("SCHEDULER_POOL_SIZE: ${SCHEDULER_POOL_SIZE:-14}");
        assertThat(example).contains("Scheduler threads of the api, 14 to 32 [14]");
        assertThat(compose).doesNotContain("RISKCONTROL").doesNotContain("RISK_PURGE");
    }

    @Test
    void thePropertiesRefuseValuesOutsideTheirDomain() {
        RiskControlProperties defaults = RiskControlProperties.defaults();
        assertThat(defaults.freshnessMaxAge()).isEqualTo(Duration.ofSeconds(60));
        assertThat(defaults.purge().batchSize()).isEqualTo(1000);
        assertThat(defaults.purge().selectionAge()).isEqualTo(Duration.ofDays(31));

        assertThatThrownBy(() -> new RiskControlProperties(Duration.ofSeconds(300), defaults.purge()))
                .hasMessageContaining("freshness-max-age");
        assertThatThrownBy(() -> purge(0, Duration.ofDays(31), Duration.ofDays(30)))
                .hasMessageContaining("batch-size");
        assertThatThrownBy(() -> purge(5001, Duration.ofDays(31), Duration.ofDays(30)))
                .hasMessageContaining("batch-size");
        assertThatCode(() -> purge(5000, Duration.ofDays(31), Duration.ofDays(30)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> purge(1000, Duration.ofDays(30), Duration.ofDays(30)))
                .hasMessageContaining("selection-age");
        assertThatThrownBy(() -> purge(1000, Duration.ofDays(31), Duration.ofDays(29)))
                .hasMessageContaining("trigger-age");
        assertThatThrownBy(() -> new Purge(
                        "0 30 3 * * *",
                        1000,
                        Duration.ofMinutes(45),
                        Duration.ofMinutes(30),
                        Duration.ofDays(31),
                        Duration.ofDays(30)))
                .hasMessageContaining("lock-at-most-for");
        assertThatThrownBy(() -> new Purge(
                        " ",
                        1000,
                        Duration.ofMinutes(30),
                        Duration.ofMinutes(45),
                        Duration.ofDays(31),
                        Duration.ofDays(30)))
                .hasMessageContaining("cron");
    }

    private static Purge purge(int batchSize, Duration selectionAge, Duration triggerAge) {
        return new Purge(
                "0 30 3 * * *", batchSize, Duration.ofMinutes(30), Duration.ofMinutes(45), selectionAge, triggerAge);
    }

    private static MockEnvironment environment(String scheduler) {
        return new MockEnvironment()
                .withProperty("spring.datasource.hikari.maximum-pool-size", "20")
                .withProperty("spring.task.scheduling.pool.size", scheduler);
    }
}
