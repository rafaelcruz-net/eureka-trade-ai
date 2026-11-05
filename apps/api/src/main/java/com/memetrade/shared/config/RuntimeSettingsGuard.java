package com.memetrade.shared.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Start-up range check of the pool sizes, per process (U4 infrastructure-specification.md, checked per process since
 * U5, NFR2.4). A value out of range stops the process with a message naming the variable.
 *
 * <ul>
 *   <li><b>api</b>: main connection pool ({@code DB_POOL_MAX_SIZE}) 10 to 50, scheduler pool
 *       ({@code SCHEDULER_POOL_SIZE}) 14 to 32 - one thread per {@code @Scheduled} method of the api: U1 to U3 (5)
 *       audit relay, idempotency cleanup, audit retention purge, audit outbox cleanup, login limiter eviction; U4 (3)
 *       collection dispatcher, history cleanup, scanner limiter eviction; U7 (4) marking, pending close,
 *       reconciliation, "check now" limiter eviction; U6 (1) purge of the old approved evaluations; U5 (1) the live
 *       channel mark poller ({@code DashboardMarkPoller}).
 *   <li><b>worker</b>: pool 14 to 30 (4 cycles, 4 reserve, 6 jobs, scalability-design.md), scheduler 4 to 8 (slot
 *       dispatch, midnight, closing check, decision purge).
 * </ul>
 *
 * <p>{@code MEMETRADE_PROCESS} itself must be {@code api} or {@code worker}.
 */
@Component
public class RuntimeSettingsGuard {

    static final String POOL_PROPERTY = "spring.datasource.hikari.maximum-pool-size";
    static final String SCHEDULER_PROPERTY = "spring.task.scheduling.pool.size";

    /** The smallest scheduler pool of the api: its 14 scheduled jobs (13 of U1 to U7 plus the U5 mark poller). */
    public static final int MIN_SCHEDULER_POOL = 14;

    /** The smallest scheduler pool of the worker: its 4 scheduled jobs (U5). */
    public static final int MIN_WORKER_SCHEDULER_POOL = 4;

    /** The smallest connection pool of the worker (scalability-design.md: 4 cycles, 4 reserve, 6 jobs). */
    public static final int MIN_WORKER_POOL = 14;

    public RuntimeSettingsGuard(Environment environment) {
        ProcessRole.Kind process = ProcessRole.Kind.parse(environment.getProperty(ProcessRole.PROPERTY));
        if (process == ProcessRole.Kind.WORKER) {
            check(environment, POOL_PROPERTY, "DB_POOL_MAX_SIZE", MIN_WORKER_POOL, 30);
            check(environment, SCHEDULER_PROPERTY, "SCHEDULER_POOL_SIZE", MIN_WORKER_SCHEDULER_POOL, 8);
        } else {
            check(environment, POOL_PROPERTY, "DB_POOL_MAX_SIZE", 10, 50);
            check(environment, SCHEDULER_PROPERTY, "SCHEDULER_POOL_SIZE", MIN_SCHEDULER_POOL, 32);
        }
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
