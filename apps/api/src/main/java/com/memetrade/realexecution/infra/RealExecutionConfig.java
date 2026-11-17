package com.memetrade.realexecution.infra;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Wiring of the REAL execution (U4 of 261113-trading-real-bot). The settings are checked once at start-up in the api
 * and in the worker ({@link RealExecutionSettings}); a violation stops the context naming the setting.
 */
@Configuration(proxyBeanMethods = false)
public class RealExecutionConfig {

    /** The checked settings; every other bean of the module depends on them. */
    @Bean
    public CheckedSettings realExecutionCheckedSettings(RealExecutionProperties properties, Environment environment) {
        List<String> violations = RealExecutionSettings.violations(
                properties,
                environment.acceptsProfiles(Profiles.of("test", "local")),
                environment.acceptsProfiles(Profiles.of("production")),
                environment.getProperty("memetrade.real.network", "LOCALNET"));
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "The REAL execution settings are invalid: " + String.join("; ", violations));
        }
        return new CheckedSettings(properties);
    }

    /** In production nothing happens at the crash points; only the tests stop a worker there. */
    @Bean
    public CrashPoints crashPoints() {
        return CrashPoints.NONE;
    }

    /**
     * 261113-trading-real-bot U7 (logical-components.md "Ajustes menores"): the empty implementations of the extension
     * interfaces of the M6, used only when the module {@code realexits} is not in the context: every position is free to
     * sell and no conditional is ever announced.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(
            com.memetrade.realexecution.api.PositionExitGuard.class)
    public com.memetrade.realexecution.api.PositionExitGuard noExitGuard() {
        return (tenantId, userId, mint) -> com.memetrade.realexecution.api.PositionExitGuard.ExitRelease.READY;
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(
            com.memetrade.realexecution.api.RealExitChanges.class)
    public com.memetrade.realexecution.api.RealExitChanges noExitChanges() {
        return new com.memetrade.realexecution.api.RealExitChanges() {
            @Override
            public long changeMark(java.util.UUID tenantId, java.util.UUID userId) {
                return 0;
            }

            @Override
            public List<com.memetrade.realexecution.api.RealExitChanges.ConditionalChange> changedSince(
                    java.util.UUID tenantId, java.util.UUID userId, java.time.Instant since, int limit) {
                return List.of();
            }
        };
    }

    /** The settings after the start-up check. */
    public record CheckedSettings(RealExecutionProperties properties) {}
}
