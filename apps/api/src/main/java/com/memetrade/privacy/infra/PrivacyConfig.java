package com.memetrade.privacy.infra;

import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.bots.api.BotOwnerDataPort;
import com.memetrade.feedback.api.FeedbackOwnerDataPort;
import com.memetrade.identity.api.AccountAnonymizationPort;
import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.api.PersonDataPort;
import com.memetrade.privacy.domain.anonymization.AnonymizationEvents;
import com.memetrade.privacy.domain.anonymization.AnonymizationOutcomes;
import com.memetrade.privacy.domain.anonymization.AnonymizationService;
import com.memetrade.privacy.domain.anonymization.AnonymizationTimeouts;
import com.memetrade.privacy.domain.purge.PrivacyPurge;
import com.memetrade.privacy.domain.purge.PurgeHealthPolicy;
import com.memetrade.privacy.domain.purge.PurgeRunStore;
import com.memetrade.privacy.domain.request.AccessSummarySource;
import com.memetrade.privacy.domain.request.DataRequestEvents;
import com.memetrade.privacy.domain.request.DataRequestRateLimit;
import com.memetrade.privacy.domain.request.DataRequestService;
import com.memetrade.privacy.domain.request.DataRequestStore;
import com.memetrade.privacy.domain.request.DataRequestTimers;
import com.memetrade.privacy.domain.request.IdentityVerificationService;
import com.memetrade.privacy.domain.request.VerificationStore;
import com.memetrade.shared.config.ProcessRole;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the data subject requests of u4-data-requests (logical-components.md): the domain services (plain classes),
 * and - only in the api process - the jobs, their runner and recovery and the two health components. The bean names of
 * the health indicators drop the {@code HealthIndicator} suffix in Actuator: {@code dataRequests} and
 * {@code dataSubjectRequestPurge}; neither is in the liveness or readiness group.
 */
@Configuration(proxyBeanMethods = false)
public class PrivacyConfig {

    @Bean
    public DataRequestService dataRequestService(
            DataRequestStore store,
            VerificationStore verifications,
            IdempotencyGuard idempotency,
            DataRequestRateLimit rateLimit,
            DataRequestEvents events,
            PersonDataPort people,
            AccessSummarySource summaries,
            Clock clock,
            PrivacyProperties properties) {
        return new DataRequestService(
                store,
                verifications,
                idempotency,
                rateLimit,
                events,
                people,
                summaries,
                clock,
                properties.responseTime());
    }

    @Bean
    public IdentityVerificationService identityVerificationService(
            DataRequestStore store,
            VerificationStore verifications,
            IdempotencyGuard idempotency,
            DataRequestEvents events,
            PersonDataPort people,
            Clock clock,
            PrivacyProperties properties,
            @Value("${memetrade.auth.guest-app-origin}") URI guestAppOrigin) {
        return new IdentityVerificationService(
                store,
                verifications,
                idempotency,
                events,
                people,
                clock,
                properties.verificationTtl(),
                domain(guestAppOrigin));
    }

    /** u5-anonymization (logical-components.md): the anonymization of the account of a DELETION request. */
    @Bean
    public AnonymizationService anonymizationService(
            DataRequestStore store,
            IdempotencyGuard idempotency,
            AccountAnonymizationPort accounts,
            BotOwnerDataPort bots,
            FeedbackOwnerDataPort feedback,
            AnonymizationEvents events,
            DataRequestEvents requestEvents,
            AnonymizationOutcomes outcomes,
            AnonymizationTimeouts timeouts,
            Clock clock,
            com.memetrade.botwallet.api.RealAnonymization realWallets) {
        return new AnonymizationService(
                store,
                idempotency,
                accounts,
                bots,
                feedback,
                events,
                requestEvents,
                outcomes,
                timeouts,
                clock,
                realWallets);
    }

    @Bean
    public DataRequestTimers dataRequestTimers(
            DataRequestStore store, DataRequestEvents events, Clock clock, PrivacyProperties properties) {
        return new DataRequestTimers(store, events, clock, properties.timersBatchSize());
    }

    @Bean
    public DataSubjectRequestPurgeJob dataSubjectRequestPurgeJob(
            JdbcDataRequestStore store, PrivacyProperties properties) {
        return new DataSubjectRequestPurgeJob(store, properties.retentionMonths());
    }

    /** NFR2.3: the domain of the verification message is the host (and port) of the guest app, like the login. */
    static String domain(URI guestAppOrigin) {
        return guestAppOrigin.getPort() < 0
                ? guestAppOrigin.getHost()
                : guestAppOrigin.getHost() + ":" + guestAppOrigin.getPort();
    }

    /** The jobs and the health of the requests, only in the api process. */
    @Configuration(proxyBeanMethods = false)
    @ProcessRole(ProcessRole.Kind.API)
    static class Jobs {

        @Bean
        public PrivacyJobRunner privacyJobRunner(
                LockingTaskExecutor locks,
                List<PrivacyPurgeJob> purgeJobs,
                PurgeRunStore runs,
                AuditEventPublisher audit,
                PlatformTransactionManager transactions,
                PrivacyJobMetrics metrics,
                PrivacyProperties settings,
                Clock clock) {
            return new PrivacyJobRunner(locks, purgeJobs, runs, audit, transactions, metrics, settings, clock);
        }

        @Bean
        public PrivacyJobRecovery privacyJobRecovery(
                PurgeRunStore runs,
                PrivacyJobRunner runner,
                PrivacyJobMetrics metrics,
                PrivacyProperties settings,
                Clock clock) {
            return new PrivacyJobRecovery(runs, runner, metrics, settings, clock);
        }

        @Bean
        public DataRequestTimersJob dataRequestTimersJob(
                LockingTaskExecutor locks,
                DataRequestTimers timers,
                PlatformTransactionManager transactions,
                PrivacyProperties settings,
                Clock clock) {
            return new DataRequestTimersJob(locks, timers, transactions, settings, clock);
        }

        @Bean("dataRequestsHealthIndicator")
        public DataRequestsHealthIndicator dataRequestsHealth(DataRequestMetrics metrics) {
            return new DataRequestsHealthIndicator(metrics);
        }

        @Bean("dataSubjectRequestPurgeHealthIndicator")
        public PrivacyPurgeHealthIndicator dataSubjectRequestPurgeHealth(
                PurgeRunStore runs, PrivacyProperties settings, Clock clock) {
            return new PrivacyPurgeHealthIndicator(
                    PrivacyPurge.DATA_SUBJECT_REQUEST_PURGE,
                    runs,
                    new PurgeHealthPolicy(settings.degradedAfter(), settings.recoverAfter()),
                    clock.instant(),
                    clock);
        }
    }
}
