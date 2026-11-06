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
 *       channel mark poller ({@code DashboardMarkPoller}); U8 (2) the kill switch reconciliation
 *       ({@code KillSwitchReconciler}) and the feedback purge ({@code FeedbackPurgeJob}). U8 also checks the origin of
 *       the administrator app ({@code ADMIN_APP_ORIGIN}): an http or https origin, never a wildcard.
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

    /** U8: origin of the administrator app accepted by the administrative CORS policy. */
    static final String ADMIN_ORIGIN_PROPERTY = "memetrade.admin.app-origin";

    /**
     * The smallest scheduler pool of the api: its 16 scheduled jobs (13 of U1 to U7, the U5 mark poller and the two
     * jobs of U8).
     */
    public static final int MIN_SCHEDULER_POOL = 16;

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
            checkAdminOrigin(environment.getProperty(ADMIN_ORIGIN_PROPERTY));
        }
    }

    /**
     * U8 (security-design.md, CORS): one explicit origin, {@code http} or {@code https} with a host, no path and never
     * a wildcard. An absent value keeps the shipped default of {@code application.yml}.
     */
    static void checkAdminOrigin(String raw) {
        if (raw == null) {
            return;
        }
        String value = raw.trim();
        java.net.URI origin;
        try {
            origin = java.net.URI.create(value);
        } catch (IllegalArgumentException malformed) {
            throw invalidAdminOrigin();
        }
        boolean web = "http".equals(origin.getScheme()) || "https".equals(origin.getScheme());
        boolean bare = origin.getHost() != null
                && (origin.getPath() == null || origin.getPath().isEmpty() || "/".equals(origin.getPath()))
                && origin.getQuery() == null
                && origin.getUserInfo() == null;
        if (value.contains("*") || value.contains(",") || !web || !bare) {
            throw invalidAdminOrigin();
        }
    }

    private static IllegalArgumentException invalidAdminOrigin() {
        return new IllegalArgumentException(
                "ADMIN_APP_ORIGIN must be one http(s) origin such as http://localhost:3001, never a wildcard");
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
