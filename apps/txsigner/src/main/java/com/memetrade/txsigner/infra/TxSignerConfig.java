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

    /**
     * 261113-trading-real-bot U7 (BR0.3, U7-NFR1.2): the program of the Jupiter Trigger recorded by
     * {@code record.sh --trigger}, from the resources {@code trigger-program.json} and {@code trigger-program.json.sha256};
     * a file that does not match its hash (or {@code txsigner.trigger.program-sha256}) stops the start. Without the
     * recording the TxSigner starts as before and refuses the trigger operations with {@code POLICY_NOT_RECORDED}.
     */
    @Bean
    com.memetrade.txsigner.policy.TriggerProgram triggerProgram(org.springframework.core.env.Environment environment) {
        com.memetrade.txsigner.policy.TriggerProgram program = TriggerProgramLoader.load(
                resource("trigger-program.json"),
                resource("trigger-program.json.sha256")
                        .map(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8)),
                environment.getProperty("txsigner.trigger.program-sha256", ""),
                environment.acceptsProfiles(org.springframework.core.env.Profiles.of("production")));
        org.slf4j.LoggerFactory.getLogger(TxSignerConfig.class)
                .info("Trigger program recorded: {}", program.recorded());
        return program;
    }

    private static java.util.Optional<byte[]> resource(String name) {
        org.springframework.core.io.ClassPathResource resource =
                new org.springframework.core.io.ClassPathResource(name);
        if (!resource.exists()) {
            return java.util.Optional.empty();
        }
        try (java.io.InputStream in = resource.getInputStream()) {
            return java.util.Optional.of(in.readAllBytes());
        } catch (java.io.IOException unreadable) {
            throw new IllegalStateException(name + " could not be read", unreadable);
        }
    }

    /** U7 (U7-NFR5.4): {@code txsigner_trigger_refusals_total{reason}}, registered with zero at the start. */
    @Bean
    java.util.List<io.micrometer.core.instrument.Counter> triggerRefusalCounters(MeterRegistry meters) {
        return com.memetrade.txsigner.policy.PolicyRefusal.TRIGGER_REASONS.stream()
                .map(reason -> io.micrometer.core.instrument.Counter.builder("txsigner_trigger_refusals")
                        .tag("reason", reason.name())
                        .description("Trigger operations refused by the TxSigner, by reason")
                        .register(meters))
                .toList();
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
