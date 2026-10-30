package com.memetrade.audit.infra;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the AuditTrail (reliability-design.md, observability-design.md), validated at startup: the API does
 * not start with a retention shorter than 12 months (BR3.1, NFR7.4) or with job limits outside the design.
 *
 * @param relayInterval relay cadence (U1)
 * @param relayBatchSize rows per relay cycle (U1)
 * @param maxDeliveryLag age of the oldest pending row above which {@code auditRelay} is DEGRADED (U1)
 * @param jobs daily retention purge and outbox cleanup (U2)
 */
@ConfigurationProperties(prefix = "memetrade.audit")
public record AuditProperties(Duration relayInterval, int relayBatchSize, Duration maxDeliveryLag, Jobs jobs) {

    public AuditProperties {
        Objects.requireNonNull(relayInterval, "memetrade.audit.relay-interval");
        Objects.requireNonNull(maxDeliveryLag, "memetrade.audit.max-delivery-lag");
        if (relayBatchSize < 1) {
            throw new IllegalArgumentException("memetrade.audit.relay-batch-size must be at least 1");
        }
        jobs = jobs == null ? Jobs.defaults() : jobs;
    }

    /** U1 relay settings with the default job settings (tests and the U1 call sites). */
    public static AuditProperties relayOnly(Duration relayInterval, int relayBatchSize, Duration maxDeliveryLag) {
        return new AuditProperties(relayInterval, relayBatchSize, maxDeliveryLag, Jobs.defaults());
    }

    /**
     * Retention purge and outbox cleanup. Cron expressions run in UTC (the zone is fixed in the schedule).
     *
     * @param retentionMonths months an event stays in the trail; never below 12 (BR3.1)
     * @param outboxDeliveredRetention how long a delivered outbox row is kept (BR3.4, 7 days)
     * @param retentionCron schedule of the purge (03:00 UTC)
     * @param outboxCleanupCron schedule of the cleanup (03:30 UTC)
     * @param timeLimit a job stops before starting a batch once this much time has passed (30 min, NFR4.4)
     * @param batchSize rows per batch transaction (at most 1000, NFR3.3)
     * @param lockAtMostFor ShedLock upper bound (35 min)
     * @param lockAtLeastFor ShedLock lower bound (5 min)
     * @param recoverAfter a job without a success for longer than this runs when the API starts (24 h)
     * @param degradedAfter a job without a success for longer than this makes its health DEGRADED (26 h)
     */
    public record Jobs(
            int retentionMonths,
            Duration outboxDeliveredRetention,
            String retentionCron,
            String outboxCleanupCron,
            Duration timeLimit,
            int batchSize,
            Duration lockAtMostFor,
            Duration lockAtLeastFor,
            Duration recoverAfter,
            Duration degradedAfter) {

        public static final int MIN_RETENTION_MONTHS = 12;
        public static final int MAX_BATCH_SIZE = 1000;

        public Jobs {
            if (retentionMonths < MIN_RETENTION_MONTHS) {
                throw new IllegalArgumentException(
                        "memetrade.audit.jobs.retention-months must be at least 12 (BR3.1); got " + retentionMonths);
            }
            if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
                throw new IllegalArgumentException("memetrade.audit.jobs.batch-size must be between 1 and 1000");
            }
            requirePositive(outboxDeliveredRetention, "outbox-delivered-retention");
            requirePositive(timeLimit, "time-limit");
            requirePositive(lockAtMostFor, "lock-at-most-for");
            requirePositive(recoverAfter, "recover-after");
            requirePositive(degradedAfter, "degraded-after");
            Objects.requireNonNull(lockAtLeastFor, "memetrade.audit.jobs.lock-at-least-for");
            Objects.requireNonNull(retentionCron, "memetrade.audit.jobs.retention-cron");
            Objects.requireNonNull(outboxCleanupCron, "memetrade.audit.jobs.outbox-cleanup-cron");
            if (lockAtMostFor.compareTo(timeLimit) <= 0) {
                throw new IllegalArgumentException(
                        "memetrade.audit.jobs.lock-at-most-for must be longer than the time limit");
            }
        }

        /** The values of application.yml. */
        public static Jobs defaults() {
            return new Jobs(
                    12,
                    Duration.ofDays(7),
                    "0 0 3 * * *",
                    "0 30 3 * * *",
                    Duration.ofMinutes(30),
                    1000,
                    Duration.ofMinutes(35),
                    Duration.ofMinutes(5),
                    Duration.ofHours(24),
                    Duration.ofHours(26));
        }

        private static void requirePositive(Duration value, String name) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("memetrade.audit.jobs." + name + " must be a positive duration");
            }
        }
    }
}
