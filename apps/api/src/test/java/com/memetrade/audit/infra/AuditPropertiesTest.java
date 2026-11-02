package com.memetrade.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** Startup validation of the audit settings (BR3.1, NFR4.4, NFR13.1) and the values shipped in application.yml. */
@Tag("u2")
class AuditPropertiesTest {

    private static final AuditProperties.Jobs D = AuditProperties.Jobs.defaults();

    @Test
    void theDefaultsAreTheDesignValues() {
        assertThat(D.retentionMonths()).isEqualTo(12);
        assertThat(D.outboxDeliveredRetention()).isEqualTo(Duration.ofDays(7));
        assertThat(D.retentionCron()).isEqualTo("0 0 3 * * *");
        assertThat(D.outboxCleanupCron()).isEqualTo("0 30 3 * * *");
        assertThat(D.timeLimit()).isEqualTo(Duration.ofMinutes(30));
        assertThat(D.batchSize()).isEqualTo(1000);
        assertThat(D.lockAtMostFor()).isEqualTo(Duration.ofMinutes(35));
        assertThat(D.lockAtLeastFor()).isEqualTo(Duration.ofMinutes(5));
        assertThat(D.recoverAfter()).isEqualTo(Duration.ofHours(24));
        assertThat(D.degradedAfter()).isEqualTo(Duration.ofHours(26));
    }

    @Test
    void applicationYmlShipsTheSameValues() throws IOException {
        PropertySource<?> yml = load("application.yml");

        assertThat(value(yml, "memetrade.audit.jobs.retention-months")).isEqualTo("${AUDIT_RETENTION_MONTHS:12}");
        assertThat(value(yml, "memetrade.audit.jobs.retention-cron")).isEqualTo("0 0 3 * * *");
        assertThat(value(yml, "memetrade.audit.jobs.outbox-cleanup-cron")).isEqualTo("0 30 3 * * *");
        assertThat(value(yml, "memetrade.audit.jobs.time-limit")).isEqualTo("30m");
        assertThat(value(yml, "memetrade.audit.jobs.batch-size")).isEqualTo(1000);
        assertThat(value(yml, "memetrade.audit.jobs.lock-at-most-for")).isEqualTo("35m");
        assertThat(value(yml, "memetrade.audit.jobs.lock-at-least-for")).isEqualTo("5m");
        // U4 (infrastructure-specification.md): the 3 threads of U1/U2 plus 4 for the U4 jobs.
        assertThat(value(yml, "spring.task.scheduling.pool.size")).isEqualTo("${SCHEDULER_POOL_SIZE:7}");
    }

    @Test
    void aRetentionShorterThan12MonthsStopsTheStartup() {
        assertThatThrownBy(() -> jobsWith(11, 1000, Duration.ofMinutes(30), Duration.ofMinutes(35)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 12");
        assertThat(jobsWith(13, 1000, Duration.ofMinutes(30), Duration.ofMinutes(35))
                        .retentionMonths())
                .isEqualTo(13);
    }

    @Test
    void theBatchSizeStaysBetween1And1000() {
        assertThatThrownBy(() -> jobsWith(12, 0, Duration.ofMinutes(30), Duration.ofMinutes(35)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jobsWith(12, 1001, Duration.ofMinutes(30), Duration.ofMinutes(35)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jobsWith(12, 1, Duration.ofMinutes(30), Duration.ofMinutes(35))
                        .batchSize())
                .isEqualTo(1);
    }

    @Test
    void theLockMustOutliveTheTimeLimitAndDurationsMustBePositive() {
        assertThatThrownBy(() -> jobsWith(12, 1000, Duration.ofMinutes(30), Duration.ofMinutes(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lock-at-most-for");
        assertThatThrownBy(() -> jobsWith(12, 1000, Duration.ZERO, Duration.ofMinutes(35)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("time-limit");
    }

    @Test
    void missingJobsFallBackToTheDefaultsAndTheRelayStaysValidated() {
        AuditProperties properties = new AuditProperties(Duration.ofSeconds(1), 100, Duration.ofSeconds(60), null);

        assertThat(properties.jobs()).isEqualTo(D);
        assertThatThrownBy(() -> AuditProperties.relayOnly(Duration.ofSeconds(1), 0, Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRetentionPasswordNeverAppearsInToString() {
        AuditRetentionDatabaseProperties login = new AuditRetentionDatabaseProperties(" retention ", "s3cr3t-value");

        assertThat(login.username()).isEqualTo("retention");
        assertThat(login.toString()).doesNotContain("s3cr3t-value").contains("***");
        assertThat(new AuditRetentionDatabaseProperties(null, null).password()).isEmpty();
    }

    private static AuditProperties.Jobs jobsWith(int months, int batch, Duration limit, Duration lockAtMost) {
        return new AuditProperties.Jobs(
                months,
                D.outboxDeliveredRetention(),
                D.retentionCron(),
                D.outboxCleanupCron(),
                limit,
                batch,
                lockAtMost,
                D.lockAtLeastFor(),
                D.recoverAfter(),
                D.degradedAfter());
    }

    static PropertySource<?> load(String file) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        return sources.getFirst();
    }

    static Object value(PropertySource<?> source, String name) {
        Object value = source.getProperty(name);
        if (value instanceof OriginTrackedValue tracked) {
            value = tracked.getValue();
        }
        return value instanceof CharSequence text ? text.toString() : value;
    }
}
