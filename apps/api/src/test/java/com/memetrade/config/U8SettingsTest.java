package com.memetrade.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.administration.infra.AdminProperties;
import com.memetrade.feedback.infra.FeedbackProperties;
import com.memetrade.identity.infra.AdminLoginProperties;
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
 * Passo 1 of U8 (infrastructure-specification.md, security-design.md "CORS"): the api scheduler minimum is 16, the
 * origin of the administrator app is one http(s) origin and never a wildcard, and the fixed settings of the kill switch,
 * the administrator entry and the feedback live in application.yml.
 */
@Tag("u8")
class U8SettingsTest {

    @Test
    void theApiNeedsEighteenSchedulerThreads() {
        // U8: 16 with its two jobs; u2-retention: 18 with the login challenge and session purges; u4-data-requests: 21
        // with the request timers, the request purge and the count refresher.
        assertThat(RuntimeSettingsGuard.MIN_SCHEDULER_POOL).isEqualTo(21);
        assertThatCode(() -> new RuntimeSettingsGuard(environment("21", "http://localhost:3001")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new RuntimeSettingsGuard(environment("20", "http://localhost:3001")))
                .hasMessageContaining("SCHEDULER_POOL_SIZE")
                .hasMessageContaining("between 21 and 32");
    }

    @Test
    void theAdminOriginIsOneHttpOriginAndNeverAWildcard() {
        assertThatCode(() -> new RuntimeSettingsGuard(environment("21", "https://admin.example.org")))
                .doesNotThrowAnyException();
        for (String invalid : new String[] {
            "*",
            "http://*.example.org",
            "http://localhost:3001,http://localhost:3000",
            "ftp://localhost:3001",
            "http://localhost:3001/admin",
            "localhost:3001",
            "http://user@localhost:3001"
        }) {
            assertThatThrownBy(() -> new RuntimeSettingsGuard(environment("21", invalid)))
                    .as(invalid)
                    .hasMessageContaining("ADMIN_APP_ORIGIN");
        }
    }

    @Test
    void theWorkerIgnoresTheAdminOrigin() {
        MockEnvironment worker = new MockEnvironment()
                .withProperty("memetrade.process", "worker")
                // 261113-trading-real-bot U4: the worker pools of the REAL execution (27 connections, 19 threads).
                .withProperty(
                        "spring.datasource.hikari.maximum-pool-size",
                        String.valueOf(RuntimeSettingsGuard.MIN_WORKER_POOL))
                .withProperty(
                        "spring.task.scheduling.pool.size",
                        String.valueOf(RuntimeSettingsGuard.MIN_WORKER_SCHEDULER_POOL))
                .withProperty("memetrade.admin.app-origin", "*");

        assertThatCode(() -> new RuntimeSettingsGuard(worker)).doesNotThrowAnyException();
    }

    @Test
    void applicationYmlShipsTheFixedSettingsOfU8() throws IOException {
        PropertySource<?> yml = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .getFirst();

        assertThat(String.valueOf(yml.getProperty("spring.task.scheduling.pool.size")))
                .isEqualTo("${SCHEDULER_POOL_SIZE:21}");
        assertThat(String.valueOf(yml.getProperty("memetrade.admin.app-origin")))
                .isEqualTo("${ADMIN_APP_ORIGIN:http://localhost:3001}");
        assertThat(String.valueOf(yml.getProperty("memetrade.admin.kill-switch.reconcile-interval")))
                .isEqualTo("30s");
        assertThat(String.valueOf(yml.getProperty("memetrade.admin.kill-switch.apply-lock-wait")))
                .isEqualTo("10s");
        assertThat(String.valueOf(yml.getProperty("memetrade.admin.login.per-ip-per-minute")))
                .isEqualTo("5");
        assertThat(String.valueOf(yml.getProperty("memetrade.admin.login.session-max-age")))
                .isEqualTo("8h");
        assertThat(String.valueOf(yml.getProperty("memetrade.admin.feedback.retention")))
                .isEqualTo("180d");
        assertThat(String.valueOf(yml.getProperty("memetrade.admin.feedback.purge-cron")))
                .isEqualTo("0 0 3 * * *");
        for (String key : new String[] {
            "memetrade.admin.kill-switch.reconcile-interval",
            "memetrade.admin.login.per-address-per-minute",
            "memetrade.admin.feedback.max-per-hour"
        }) {
            assertThat(String.valueOf(yml.getProperty(key))).as(key).doesNotContain("${");
        }
    }

    @Test
    void theComposeFileAndTheEnvExampleCarryTheAdminOriginAndSixteenThreads() throws IOException {
        String compose = Files.readString(Path.of("..", "..", "docker-compose.yml"), StandardCharsets.UTF_8);
        String example = Files.readString(Path.of("..", "..", ".env.example"), StandardCharsets.UTF_8);

        assertThat(compose)
                .contains("SCHEDULER_POOL_SIZE: ${SCHEDULER_POOL_SIZE:-21}")
                .contains("ADMIN_APP_ORIGIN: ${ADMIN_APP_ORIGIN:-http://localhost:3001}");
        // u2-retention raised the default from 16 to 18 (the login challenge and session purges); u4-data-requests to
        // 21.
        assertThat(example).contains("\nADMIN_APP_ORIGIN=\n").contains("Scheduler threads of the api, 21 to 32 [21]");
    }

    @Test
    void thePropertyRecordsFallBackToTheDesignValuesAndRefuseNonsense() {
        AdminProperties.KillSwitch defaults = AdminProperties.KillSwitch.defaults();
        assertThat(defaults.reconcileInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.applyLockWait()).isEqualTo(Duration.ofSeconds(10));
        assertThat(defaults.applyAttempts()).isEqualTo(3);
        assertThat(new AdminProperties(null).killSwitch()).isEqualTo(defaults);
        assertThatThrownBy(() -> new AdminProperties.KillSwitch(Duration.ZERO, null, null, 0, null, null, null))
                .hasMessageContaining("reconcile-interval");
        assertThatThrownBy(() -> new AdminProperties.KillSwitch(
                        null, Duration.ofMillis(100), Duration.ofSeconds(1), 0, null, null, null))
                .hasMessageContaining("apply-lock-poll");

        FeedbackProperties feedback = FeedbackProperties.defaults();
        assertThat(feedback.maxPerHour()).isEqualTo(10);
        assertThat(feedback.retention()).isEqualTo(Duration.ofDays(180));
        assertThat(feedback.pageMax()).isEqualTo(100);
        assertThatThrownBy(() -> new FeedbackProperties(0, null, null, 6000, null, null, 0))
                .hasMessageContaining("purge-batch-size");

        AdminLoginProperties login = new AdminLoginProperties(0, 0, null, null);
        assertThat(login.accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(login.sessionMaxAge()).isEqualTo(Duration.ofHours(8));
        assertThatThrownBy(() -> new AdminLoginProperties(5, 5, Duration.ofHours(9), Duration.ofHours(8)))
                .hasMessageContaining("access-token-ttl");
    }

    private static MockEnvironment environment(String scheduler, String adminOrigin) {
        return new MockEnvironment()
                .withProperty("memetrade.process", "api")
                .withProperty("spring.datasource.hikari.maximum-pool-size", "20")
                .withProperty("spring.task.scheduling.pool.size", scheduler)
                .withProperty("memetrade.admin.app-origin", adminOrigin);
    }
}
