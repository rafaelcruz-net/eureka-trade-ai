package com.memetrade.txsigner.infra;

import com.memetrade.txsigner.domain.RecoveryPhase;
import com.memetrade.txsigner.domain.RecoveryService;
import com.memetrade.txsigner.domain.SignerEvents;
import com.memetrade.txsigner.domain.SignerJobs;
import com.memetrade.txsigner.domain.SignerSettings;
import com.memetrade.txsigner.domain.SignerStore;
import com.memetrade.txsigner.domain.kms.KmsPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Recovery mode and jobs of the TxSigner (NFR4.13, reliability-design.md "Trabalhos"): the recovery gate read by every
 * decision, the hourly nonce cleanup and the daily orphan key check, each run once across instances through ShedLock
 * on the TxSigner's own database. The jobs are off in the migrate profile ({@code txsigner.jobs.enabled=false}).
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "30m")
public class RecoveryConfig {

    private static final Logger LOG = LoggerFactory.getLogger(RecoveryConfig.class);

    @Bean
    RecoveryService recoveryService(
            SignerSettings settings,
            Clock clock,
            KmsPort kms,
            SignerStore store,
            SignerEvents events,
            TxSignerProperties properties) {
        return new RecoveryService(
                settings,
                clock,
                kms,
                store,
                events,
                properties.recovery().enabled(),
                properties.recovery().waitDuration());
    }

    @Bean
    SignerJobs signerJobs(Clock clock, KmsPort kms, SignerStore store, SignerEvents events, TxSignerProperties props) {
        return new SignerJobs(clock, kms, store, events, props.jobs().orphanKeyMinAge());
    }

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName("shedlock")
                .usingDbTime()
                .build());
    }

    @Bean
    Gauge recoveryPhase(RecoveryService recovery, MeterRegistry meters) {
        return Gauge.builder("txsigner_recovery_phase", recovery, r -> r.phase().ordinal())
                .description("0 normal, 1 waiting 150 blocks, 2 withdrawals only")
                .register(meters);
    }

    @EventListener(ApplicationReadyEvent.class)
    void startRecovery(ApplicationReadyEvent ready) {
        RecoveryService recovery = ready.getApplicationContext().getBean(RecoveryService.class);
        recovery.start();
        if (recovery.phase() != RecoveryPhase.NORMAL) {
            LOG.warn("TxSigner started in recovery mode: nothing is signed during the wait, then only withdrawals");
        }
    }

    /** Scheduled jobs; a separate bean so the migrate profile can switch them off. */
    @Configuration
    @ConditionalOnProperty(name = "txsigner.jobs.enabled", havingValue = "true", matchIfMissing = true)
    static class Jobs {

        private final SignerJobs jobs;
        private final AtomicInteger orphanKeys = new AtomicInteger();

        Jobs(SignerJobs jobs, MeterRegistry meters) {
            this.jobs = jobs;
            Gauge.builder("txsigner_orphan_keys", orphanKeys, AtomicInteger::get)
                    .description("Bot wallet keys without policy found by the last daily check")
                    .register(meters);
        }

        @Scheduled(cron = "${txsigner.jobs.nonce-cleanup-cron}", zone = "UTC")
        @SchedulerLock(name = "txsignerNonceCleanup", lockAtMostFor = "30m", lockAtLeastFor = "1m")
        void purgeNonces() {
            int purged = jobs.purgeNonces();
            LOG.info("Nonce cleanup purged {} nonces", purged);
        }

        @Scheduled(cron = "${txsigner.jobs.orphan-keys-cron}", zone = "UTC")
        @SchedulerLock(name = "txsignerOrphanKeys", lockAtMostFor = "30m", lockAtLeastFor = "5m")
        void reportOrphanKeys() {
            orphanKeys.set(jobs.reportOrphanKeys());
        }
    }
}
