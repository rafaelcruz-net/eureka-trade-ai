package com.memetrade.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.config.RuntimeSettingsGuard;
import java.io.IOException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Passo 1 of U5 (NFR2.4, NFR10.9, infrastructure-specification.md "Configuração"): the guard checks the pools per
 * process, the process name is api or worker, the bot settings are fixed in application.yml, the worker profile carries
 * its own pools, and the U4 collection interval stays at 30 s (performance-design.md: the cycle at slotStart + 60 s
 * relies on it; changing it fails here until the design is reviewed).
 */
@Tag("u5")
class U5SettingsTest {

    @Test
    void theApiGuardAcceptsEighteenToThirtyTwoSchedulerThreads() {
        // U8 (Passo 1): two more jobs in the api, the kill switch reconciliation and the feedback purge;
        // u2-retention (Passo 10): two more, the login challenge and session purges.
        // u4-data-requests: three more, the request timers, the request purge and the count refresher.
        assertThat(RuntimeSettingsGuard.MIN_SCHEDULER_POOL).isEqualTo(23);
        assertThatCode(() -> new RuntimeSettingsGuard(environment("api", "20", "23")))
                .doesNotThrowAnyException();
        assertThatCode(() -> new RuntimeSettingsGuard(environment("api", "20", "32")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new RuntimeSettingsGuard(environment("api", "20", "22")))
                .hasMessageContaining("SCHEDULER_POOL_SIZE")
                .hasMessageContaining("between 23 and 32");
        assertThatThrownBy(() -> new RuntimeSettingsGuard(environment("api", "20", "4")))
                .hasMessageContaining("SCHEDULER_POOL_SIZE");
    }

    /** 261113-trading-real-bot U5 (U5-NFR3.7) and U7: 28 to 32 threads, 36 to 48 connections in the worker. */
    @Test
    void theWorkerGuardAcceptsTwentyFiveToThirtyTwoThreadsAndThirtyThreeToFortyEightConnections() {
        assertThatCode(() -> new RuntimeSettingsGuard(environment("worker", "36", "28")))
                .doesNotThrowAnyException();
        assertThatCode(() -> new RuntimeSettingsGuard(environment("worker", "48", "32")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new RuntimeSettingsGuard(environment("worker", "35", "28")))
                .hasMessageContaining("DB_POOL_MAX_SIZE")
                .hasMessageContaining("between 36 and 48");
        assertThatThrownBy(() -> new RuntimeSettingsGuard(environment("worker", "49", "28")))
                .hasMessageContaining("DB_POOL_MAX_SIZE");
        assertThatThrownBy(() -> new RuntimeSettingsGuard(environment("worker", "36", "27")))
                .hasMessageContaining("SCHEDULER_POOL_SIZE")
                .hasMessageContaining("between 28 and 32");
        assertThatThrownBy(() -> new RuntimeSettingsGuard(environment("worker", "36", "33")))
                .hasMessageContaining("SCHEDULER_POOL_SIZE");
    }

    @Test
    void theProcessIsApiByDefaultAndOnlyApiOrWorker() {
        assertThat(ProcessRole.Kind.parse(null)).isEqualTo(ProcessRole.Kind.API);
        assertThat(ProcessRole.Kind.parse(" ")).isEqualTo(ProcessRole.Kind.API);
        assertThat(ProcessRole.Kind.parse("Worker")).isEqualTo(ProcessRole.Kind.WORKER);
        assertThat(ProcessRole.Kind.WORKER.propertyValue()).isEqualTo("worker");
        assertThatThrownBy(() -> ProcessRole.Kind.parse("both")).hasMessageContaining("MEMETRADE_PROCESS");
        assertThatThrownBy(() -> new RuntimeSettingsGuard(environment("scheduler", "20", "14")))
                .hasMessageContaining("MEMETRADE_PROCESS");
    }

    @Test
    void theBotSettingsAreFixedInApplicationYml() throws IOException {
        PropertySource<?> yml = yaml("application.yml");

        assertThat(String.valueOf(yml.getProperty("memetrade.process"))).isEqualTo("${MEMETRADE_PROCESS:api}");
        assertThat(String.valueOf(yml.getProperty("memetrade.bots.scheduler.enabled")))
                .isEqualTo("false");
        assertThat(String.valueOf(yml.getProperty("memetrade.bots.slot-cron"))).isEqualTo("0 1/5 * * * *");
        assertThat(String.valueOf(yml.getProperty("memetrade.bots.cycle-threads")))
                .isEqualTo("4");
        assertThat(String.valueOf(yml.getProperty("memetrade.bots.virtual-balance.min")))
                .isEqualTo("100.000000");
        assertThat(String.valueOf(yml.getProperty("memetrade.bots.virtual-balance.cap")))
                .isEqualTo("10000.000000");
        assertThat(String.valueOf(yml.getProperty("memetrade.bots.decision-retention")))
                .isEqualTo("30d");
        for (String key : new String[] {
            "memetrade.bots.slot-cron",
            "memetrade.bots.cycle-threads",
            "memetrade.bots.lock-timeout",
            "memetrade.bots.statement-timeout",
            "memetrade.bots.virtual-balance.cap",
            "memetrade.bots.decision-retention"
        }) {
            assertThat(String.valueOf(yml.getProperty(key))).as(key).doesNotContain("${");
        }
    }

    @Test
    void theWorkerProfileCarriesItsOwnPoolsAndSwitchesTheBotSchedulerOn() throws IOException {
        PropertySource<?> worker = yaml("application-worker.yml");

        assertThat(String.valueOf(worker.getProperty("spring.datasource.hikari.maximum-pool-size")))
                .isEqualTo("${DB_POOL_MAX_SIZE:36}");
        assertThat(String.valueOf(worker.getProperty("spring.task.scheduling.pool.size")))
                .isEqualTo("${SCHEDULER_POOL_SIZE:28}");
        assertThat(String.valueOf(worker.getProperty("spring.datasource.hikari.connection-timeout")))
                .isEqualTo("5000");
        assertThat(String.valueOf(worker.getProperty("memetrade.bots.scheduler.enabled")))
                .isEqualTo("true");
        assertThat(String.valueOf(worker.getProperty("management.endpoint.health.group.liveness.include")))
                .doesNotContain("botCycles");
    }

    /** performance-design.md "Frescor": the U4 collection default must stay at 30 s for the cycle at slotStart + 60 s. */
    @Test
    void theU4CollectionIntervalDefaultStaysAtThirtySeconds() throws IOException {
        assertThat(String.valueOf(yaml("application.yml").getProperty("memetrade.market.collect-interval")))
                .isEqualTo("${MARKET_COLLECTION_INTERVAL:30s}");
    }

    private static PropertySource<?> yaml(String name) throws IOException {
        return new YamlPropertySourceLoader()
                .load(name, new ClassPathResource(name))
                .getFirst();
    }

    private static MockEnvironment environment(String process, String pool, String scheduler) {
        return new MockEnvironment()
                .withProperty("memetrade.process", process)
                .withProperty("spring.datasource.hikari.maximum-pool-size", pool)
                .withProperty("spring.task.scheduling.pool.size", scheduler);
    }
}
