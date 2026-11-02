package com.memetrade.shared.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Start-up range check of the pool sizes of U4 (infrastructure-specification.md): the main connection pool
 * ({@code DB_POOL_MAX_SIZE}, 10 to 50) and the scheduler pool ({@code SCHEDULER_POOL_SIZE}, 7 to 32, the 3 threads of
 * U1/U2 plus the 4 of U4). A value out of range stops the api with a message naming the variable.
 */
@Component
public class RuntimeSettingsGuard {

    static final String POOL_PROPERTY = "spring.datasource.hikari.maximum-pool-size";
    static final String SCHEDULER_PROPERTY = "spring.task.scheduling.pool.size";

    public RuntimeSettingsGuard(Environment environment) {
        check(environment, POOL_PROPERTY, "DB_POOL_MAX_SIZE", 10, 50);
        check(environment, SCHEDULER_PROPERTY, "SCHEDULER_POOL_SIZE", 7, 32);
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
