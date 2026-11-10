package com.memetrade.identity.infra;

import com.memetrade.identity.domain.purge.IdentityPurge;
import com.memetrade.identity.domain.purge.PurgeHealthPolicy;
import com.memetrade.identity.domain.purge.PurgeRunStore;
import com.memetrade.shared.config.ProcessRole;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two health components of the Identity purges (NFR7.2). The bean names drop the {@code HealthIndicator} suffix in
 * Actuator: {@code loginChallengePurge} and {@code sessionPurge}. The process start, the reference of the first 48
 * hours (BR2.8), is the instant of the injectable clock when the context creates them.
 */
@Configuration(proxyBeanMethods = false)
@ProcessRole(ProcessRole.Kind.API)
public class IdentityJobsConfig {

    @Bean("loginChallengePurgeHealthIndicator")
    public PurgeHealthIndicator loginChallengePurgeHealth(
            PurgeRunStore runs, IdentityPurgeProperties settings, Clock clock) {
        return indicator(IdentityPurge.LOGIN_CHALLENGE_PURGE, runs, settings, clock);
    }

    @Bean("sessionPurgeHealthIndicator")
    public PurgeHealthIndicator sessionPurgeHealth(PurgeRunStore runs, IdentityPurgeProperties settings, Clock clock) {
        return indicator(IdentityPurge.SESSION_PURGE, runs, settings, clock);
    }

    private static PurgeHealthIndicator indicator(
            IdentityPurge purge, PurgeRunStore runs, IdentityPurgeProperties settings, Clock clock) {
        return new PurgeHealthIndicator(
                purge,
                runs,
                new PurgeHealthPolicy(settings.degradedAfter(), settings.recoverAfter()),
                clock.instant(),
                clock);
    }
}
