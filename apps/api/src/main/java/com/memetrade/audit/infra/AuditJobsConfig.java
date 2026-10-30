package com.memetrade.audit.infra;

import com.memetrade.audit.domain.AuditJob;
import com.memetrade.audit.domain.AuditJobPolicy;
import com.memetrade.audit.domain.AuditJobRunStore;
import java.time.Clock;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ShedLock on the {@code shedlock} table (migration 0005) through the main pool, with the DATABASE clock so instances
 * with slightly different clocks agree (NFR4.6), and the two job health indicators.
 */
@Configuration(proxyBeanMethods = false)
public class AuditJobsConfig {

    @Bean
    public LockProvider auditJobLockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName("shedlock")
                .usingDbTime()
                .build());
    }

    @Bean
    public LockingTaskExecutor auditJobLocks(LockProvider auditJobLockProvider) {
        return new DefaultLockingTaskExecutor(auditJobLockProvider);
    }

    /** Health component {@code auditRetention} (the {@code HealthIndicator} suffix is dropped by Actuator). */
    @Bean("auditRetentionHealthIndicator")
    public AuditJobHealthIndicator auditRetentionHealth(
            AuditJobRunStore runs, AuditProperties properties, Clock clock) {
        return new AuditJobHealthIndicator(AuditJob.AUDIT_RETENTION_PURGE, runs, policy(properties), clock);
    }

    /** Health component {@code auditOutboxCleanup}; the bean name differs from the job bean of the same name. */
    @Bean("auditOutboxCleanupHealthIndicator")
    public AuditJobHealthIndicator auditOutboxCleanupHealth(
            AuditJobRunStore runs, AuditProperties properties, Clock clock) {
        return new AuditJobHealthIndicator(AuditJob.AUDIT_OUTBOX_CLEANUP, runs, policy(properties), clock);
    }

    private static AuditJobPolicy policy(AuditProperties properties) {
        return new AuditJobPolicy(
                properties.jobs().recoverAfter(), properties.jobs().degradedAfter());
    }
}
