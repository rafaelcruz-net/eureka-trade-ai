package com.memetrade.shared.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Start-up range check of the pool sizes, per process (U4 infrastructure-specification.md, checked per process since
 * U5, NFR2.4). A value out of range stops the process with a message naming the variable.
 *
 * <ul>
 *   <li><b>api</b>: main connection pool ({@code DB_POOL_MAX_SIZE}) 10 to 50, scheduler pool
 *       ({@code SCHEDULER_POOL_SIZE}) 23 to 32 - one thread per {@code @Scheduled} method of the api: U1 to U3 (5)
 *       audit relay, idempotency cleanup, audit retention purge, audit outbox cleanup, login limiter eviction; U4 (3)
 *       collection dispatcher, history cleanup, scanner limiter eviction; U7 (4) marking, pending close,
 *       reconciliation, "check now" limiter eviction; U6 (1) purge of the old approved evaluations; U5 (1) the live
 *       channel mark poller ({@code DashboardMarkPoller}); U8 (2) the kill switch reconciliation
 *       ({@code KillSwitchReconciler}) and the feedback purge ({@code FeedbackPurgeJob}); u2-retention (2) the login
 *       challenge purge and the session purge ({@code IdentityJobSchedule}); u4-data-requests (3) the timers and the
 *       purge of the data subject requests ({@code PrivacyJobSchedule}) and the refresher of their counts
 *       ({@code DataRequestCountsRefresher}); the U5 of 261113-trading-real-bot (1) the purge of the REAL operations
 *       ({@code RealOpsPurgeJob}); the U7 of 261113-trading-real-bot (1) the purge of the REAL exits
 *       ({@code RealExitsPurgeJob}). U8 also checks the origin of
 *       the administrator app ({@code ADMIN_APP_ORIGIN}): an http or https origin, never a wildcard. The U2 of
 *       261121-paper-real-unico checks the pool {@code pnl-mismatch-audit} ({@code memetrade.pnl.mismatch-audit.pool-size},
 *       1 to 2), the 2 connections of the bounded write of the event of a REAL P&L mismatch.
 *   <li><b>worker</b>: one scheduler thread per {@code @Scheduled} method of the worker, 28 to 32 since the U7 of
 *       261113-trading-real-bot (U5-NFR3.7, U7 pools): the bots (3: slot dispatch, midnight, decision purge), the bot wallet (3),
 *       CoinGecko (2), the REAL barrier (3 jobs and the reservation gauge), the REAL execution (7: queue, driver,
 *       confirmation, ambiguous, signing deadline, upstream health, liquidation) and the REAL operations (6: gates,
 *       reconciliation, kill switch cancel, TxSigner resend, restore check, gauges) and the REAL exits (3: conditional
 *       read, conditional cancel, exits gauges). Connection pool 36 to 48: 4 cycles, 4 reserve (the reconciliation
 *       executor of 4 uses them) and one per scheduled job.
 * </ul>
 *
 * <p>{@code MEMETRADE_PROCESS} itself must be {@code api} or {@code worker}.
 */
@Component
public class RuntimeSettingsGuard {

    static final String POOL_PROPERTY = "spring.datasource.hikari.maximum-pool-size";
    static final String SCHEDULER_PROPERTY = "spring.task.scheduling.pool.size";

    /** 261121-paper-real-unico U2: the size of the pool {@code pnl-mismatch-audit} of the api, 1 to 2. */
    static final String MISMATCH_AUDIT_POOL_PROPERTY = "memetrade.pnl.mismatch-audit.pool-size";

    /** U8: origin of the administrator app accepted by the administrative CORS policy. */
    static final String ADMIN_ORIGIN_PROPERTY = "memetrade.admin.app-origin";

    /**
     * The smallest scheduler pool of the api: its 23 scheduled jobs (13 of U1 to U7, the U5 mark poller, the two jobs
     * of U8, the login challenge and session purges of u2-retention, the timers, purge and count refresher of
     * u4-data-requests, the purge of the REAL operations of the U5 and the purge of the REAL exits of the U7 of
     * 261113-trading-real-bot).
     */
    public static final int MIN_SCHEDULER_POOL = 23;

    /**
     * The smallest scheduler pool of the worker: its 28 scheduled methods (U7 of 261113-trading-real-bot). The count
     * test ({@code RuntimeSettingsGuardWorkerCountTest}) fails when the worker gets a scheduled method more than this.
     */
    public static final int MIN_WORKER_SCHEDULER_POOL = 28;

    /** The largest scheduler pool of the worker. */
    public static final int MAX_WORKER_SCHEDULER_POOL = 32;

    /** The smallest connection pool of the worker: 4 cycles + 4 reserve + 28 scheduled jobs. */
    public static final int MIN_WORKER_POOL = 36;

    /** The largest connection pool of the worker. */
    public static final int MAX_WORKER_POOL = 48;

    public RuntimeSettingsGuard(Environment environment) {
        ProcessRole.Kind process = ProcessRole.Kind.parse(environment.getProperty(ProcessRole.PROPERTY));
        if (process == ProcessRole.Kind.WORKER) {
            check(environment, POOL_PROPERTY, "DB_POOL_MAX_SIZE", MIN_WORKER_POOL, MAX_WORKER_POOL);
            check(
                    environment,
                    SCHEDULER_PROPERTY,
                    "SCHEDULER_POOL_SIZE",
                    MIN_WORKER_SCHEDULER_POOL,
                    MAX_WORKER_SCHEDULER_POOL);
        } else {
            check(environment, POOL_PROPERTY, "DB_POOL_MAX_SIZE", 10, 50);
            check(environment, SCHEDULER_PROPERTY, "SCHEDULER_POOL_SIZE", MIN_SCHEDULER_POOL, 32);
            // 261121-paper-real-unico U2 (NFR1.16, NFR3.15): the pool pnl-mismatch-audit of the api, 2 fixed
            // connections and the threads of its executor; named by its property (no variable of the task sets it).
            check(environment, MISMATCH_AUDIT_POOL_PROPERTY, MISMATCH_AUDIT_POOL_PROPERTY, 1, 2);
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
