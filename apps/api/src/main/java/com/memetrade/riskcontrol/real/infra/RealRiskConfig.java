package com.memetrade.riskcontrol.real.infra;

import com.memetrade.botwallet.api.RealPauses;
import com.memetrade.riskcontrol.api.RealRiskGate;
import com.memetrade.riskcontrol.real.domain.RealRiskLimits;
import com.memetrade.riskcontrol.real.domain.RealRiskPolicy;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * 261113-trading-real-bot U3: the limits of the REAL barrier, checked once at start-up in the api and in the worker
 * (U3-NFR8.3), the pure policy, and the ONLY {@link RealRiskGate} bean: the policy inside
 * {@link TransactionalRealRiskGate}. A looser value than the default stops the context outside {@code test} and
 * {@code local}; a value out of range stops it everywhere.
 */
@Configuration(proxyBeanMethods = false)
public class RealRiskConfig {

    @Bean
    public RealRiskLimits realRiskLimits(RealRiskProperties properties, Environment environment) {
        return properties.limits().checked(environment.acceptsProfiles(Profiles.of("test", "local")));
    }

    @Bean
    public RealRiskPolicy realRiskPolicy() {
        return new RealRiskPolicy();
    }

    @Bean
    public RealRiskGate realRiskGate(
            WalletLock lock,
            RealGateInputsReader reader,
            JdbcRealRiskStore store,
            RealRiskPolicy policy,
            RealRiskLimits limits,
            RealPauses pauses,
            RealRiskAudit audit,
            RealRiskMetrics metrics,
            Clock clock) {
        return new TransactionalRealRiskGate(lock, reader, store, policy, limits, pauses, audit, metrics, clock);
    }
}
