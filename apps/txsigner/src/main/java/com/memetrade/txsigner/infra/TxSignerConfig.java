package com.memetrade.txsigner.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.txsigner.domain.KeyService;
import com.memetrade.txsigner.domain.RecoveryGate;
import com.memetrade.txsigner.domain.SignerEvents;
import com.memetrade.txsigner.domain.SignerSettings;
import com.memetrade.txsigner.domain.SignerStore;
import com.memetrade.txsigner.domain.SigningService;
import com.memetrade.txsigner.domain.kms.KmsPort;
import com.memetrade.txsigner.infra.audit.LoggingSignerEvents;
import com.memetrade.txsigner.infra.credential.ServiceCredentials;
import com.memetrade.txsigner.infra.db.JdbcSignerStore;
import com.memetrade.txsigner.web.CorrelationIdFilter;
import com.memetrade.txsigner.web.ServiceAuthFilter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Wiring of the TxSigner process. Fixed values come from application.yml; secrets only from the environment. */
@Configuration
public class TxSignerConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SignerSettings signerSettings(TxSignerProperties properties, org.springframework.core.env.Environment environment) {
        // U4-NFR8.1: MAINNET only with the production profile, in every profile of the TxSigner.
        com.memetrade.txsigner.infra.aws.ProductionChecks.networkViolation(
                        properties.network(), java.util.Set.of(environment.getActiveProfiles()))
                .ifPresent(violation -> {
                    throw new IllegalStateException("TxSigner network check failed: " + violation);
                });
        if (properties.maxSwapPriorityFeeLamports() < 0
                || properties.maxSwapPriorityFeeLamports() > SignerSettings.DEFAULT_MAX_SWAP_PRIORITY_FEE_LAMPORTS) {
            throw new IllegalStateException("txsigner.max-swap-priority-fee-lamports must be 0 to 1000000 (BR4.3)");
        }
        return new SignerSettings(
                properties.real().enabled(),
                properties.network(),
                properties.maxCapLamports(),
                properties.maxWithdrawalFeeLamports(),
                properties.maxWithdrawalComputeUnits(),
                properties.baseFeeLamportsPerSignature(),
                properties.maxKeysPerHour(),
                properties.maxSwapPriorityFeeLamports());
    }

    @Bean
    JdbcSignerStore signerStore(JdbcClient jdbc, TransactionTemplate transactions) {
        return new JdbcSignerStore(jdbc, transactions);
    }

    @Bean
    SignerEvents signerEvents(MeterRegistry meters) {
        return new LoggingSignerEvents(meters);
    }

    @Bean
    KeyService keyService(
            SignerSettings settings,
            Clock clock,
            KmsPort kms,
            SignerStore store,
            SignerEvents events,
            RecoveryGate recovery) {
        return new KeyService(settings, clock, kms, store, events, recovery);
    }

    @Bean
    SigningService signingService(
            SignerSettings settings,
            Clock clock,
            KmsPort kms,
            SignerStore store,
            SignerEvents events,
            RecoveryGate recovery) {
        return new SigningService(settings, clock, kms, store, events, recovery);
    }

    @Bean
    Gauge credentialAge(ServiceCredentials credentials, MeterRegistry meters) {
        return Gauge.builder("txsigner_credential_age_days", credentials, ServiceCredentials::currentAgeDays)
                .description("Age in days of the current service credential (alert at 80)")
                .register(meters);
    }

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    FilterRegistrationBean<ServiceAuthFilter> serviceAuthFilter(
            ServiceCredentials credentials, MeterRegistry meters, ObjectMapper json) {
        FilterRegistrationBean<ServiceAuthFilter> registration =
                new FilterRegistrationBean<>(new ServiceAuthFilter(credentials, meters, json));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/internal/*");
        return registration;
    }

    /** Readiness of the KMS (ListKeys): a slow KMS takes the TxSigner out of traffic without restarting it. */
    @Bean
    HealthIndicator kms(KmsPort kms) {
        return () -> kms.isReachable() ? Health.up().build() : Health.down().build();
    }
}
