package com.memetrade.identity.infra;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the Identity purges of u2-retention ({@code memetrade.identity.purge}, reliability-design.md,
 * scalability-design.md). They live only in {@code application.yml}; an absent value takes the default of the design
 * and a value outside its domain stops the api naming the property.
 *
 * @param challengesCron six-field Spring cron of the login challenge purge, evaluated in UTC (03:20)
 * @param sessionsCron six-field Spring cron of the session purge, evaluated in UTC (03:40)
 * @param challengeRetention a challenge leaves this long after it expired (24 hours, BR2.5)
 * @param sessionRetention a session leaves this long after its end (30 days, BR2.6)
 * @param batchSize rows per batch transaction (1 to 1000, NFR3.1)
 * @param lockAtMostFor ShedLock upper bound (35 minutes, NFR3.7)
 * @param lockAtLeastFor ShedLock lower bound (5 minutes, NFR3.7)
 * @param recoverAfter a purge without a success for longer than this runs when the api is ready (24 hours, BR2.16)
 * @param degradedAfter the health is DEGRADED after this long without a success and since the start (48 hours, BR2.8)
 */
@ConfigurationProperties(prefix = "memetrade.identity.purge")
public record IdentityPurgeProperties(
        String challengesCron,
        String sessionsCron,
        Duration challengeRetention,
        Duration sessionRetention,
        int batchSize,
        Duration lockAtMostFor,
        Duration lockAtLeastFor,
        Duration recoverAfter,
        Duration degradedAfter) {

    public static final int MAX_BATCH_SIZE = 1000;

    public IdentityPurgeProperties {
        challengesCron = blankToDefault(challengesCron, "0 20 3 * * *");
        sessionsCron = blankToDefault(sessionsCron, "0 40 3 * * *");
        challengeRetention = challengeRetention == null ? Duration.ofHours(24) : challengeRetention;
        sessionRetention = sessionRetention == null ? Duration.ofDays(30) : sessionRetention;
        batchSize = batchSize == 0 ? MAX_BATCH_SIZE : batchSize;
        lockAtMostFor = lockAtMostFor == null ? Duration.ofMinutes(35) : lockAtMostFor;
        lockAtLeastFor = lockAtLeastFor == null ? Duration.ofMinutes(5) : lockAtLeastFor;
        recoverAfter = recoverAfter == null ? Duration.ofHours(24) : recoverAfter;
        degradedAfter = degradedAfter == null ? Duration.ofHours(48) : degradedAfter;
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("memetrade.identity.purge.batch-size must be between 1 and 1000");
        }
        requirePositive(challengeRetention, "challenge-retention");
        requirePositive(sessionRetention, "session-retention");
        requirePositive(lockAtMostFor, "lock-at-most-for");
        requirePositive(recoverAfter, "recover-after");
        requirePositive(degradedAfter, "degraded-after");
        if (lockAtLeastFor.isNegative() || lockAtLeastFor.compareTo(lockAtMostFor) > 0) {
            throw new IllegalArgumentException(
                    "memetrade.identity.purge.lock-at-least-for must be between zero and lock-at-most-for");
        }
    }

    /** The values of application.yml. */
    public static IdentityPurgeProperties defaults() {
        return new IdentityPurgeProperties(null, null, null, null, 0, null, null, null, null);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("memetrade.identity.purge." + name + " must be a positive duration");
        }
    }
}
