package com.memetrade.shared.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Start-up range check of the pool sizes of U4 (infrastructure-specification.md): the main connection pool
 * ({@code DB_POOL_MAX_SIZE}, 10 to 50) and the scheduler pool ({@code SCHEDULER_POOL_SIZE}, 13 to 32). A value out of
 * range stops the api with a message naming the variable.
 *
 * <p>The minimum is one thread per {@code @Scheduled} method (counted in the code by U6, Passo 1):
 *
 * <ul>
 *   <li>U1 to U3 (5): audit relay, idempotency cleanup, audit retention purge, audit outbox cleanup, login limiter
 *       eviction;
 *   <li>U4 (3): market collection dispatcher, market history cleanup, scanner limiter eviction;
 *   <li>U7 (4): equity marking, pending close, reconciliation, "check now" limiter eviction;
 *   <li>U6 (1): purge of the old approved evaluations ({@code RiskEvaluationPurgeJob}).
 * </ul>
 */
@Component
public class RuntimeSettingsGuard {

    static final String POOL_PROPERTY = "spring.datasource.hikari.maximum-pool-size";
    static final String SCHEDULER_PROPERTY = "spring.task.scheduling.pool.size";

    /** U6 (NFR2.5): the smallest scheduler pool the 13 scheduled jobs of U1 to U7 fit in (12 existing plus the purge). */
    public static final int MIN_SCHEDULER_POOL = 13;

    public RuntimeSettingsGuard(Environment environment) {
        check(environment, POOL_PROPERTY, "DB_POOL_MAX_SIZE", 10, 50);
        check(environment, SCHEDULER_PROPERTY, "SCHEDULER_POOL_SIZE", MIN_SCHEDULER_POOL, 32);
    }

    static void check(Environment environment, String property, String variable, int min, int max) {
        String raw = environment.getProperty(property);
        if (raw == null) {
            return;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException notANumber) {
            throw outOfRange(variable, min, max);
        }
        if (value < min || value > max) {
            throw outOfRange(variable, min, max);
        }
    }

    private static IllegalArgumentException outOfRange(String variable, int min, int max) {
        return new IllegalArgumentException(
                variable + " is out of range: expected an integer between " + min + " and " + max);
    }
}
