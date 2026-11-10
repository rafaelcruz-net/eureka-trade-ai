package com.memetrade.privacy.infra;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the data subject requests of u4-data-requests ({@code memetrade.privacy}, infrastructure-specification.md
 * "Configuração nova"). They live only in {@code application.yml} (the {@code loadtest} profile raises the limit per
 * hour); none is a secret and none is an environment variable. An absent value takes the default of the design and a
 * value outside its domain stops the api naming the property.
 *
 * @param timersCron six-field Spring cron of the job that refuses the expired verifications and notifies the overdue
 *     requests, evaluated in UTC (every 15 minutes, BR4.13, BR4.14)
 * @param purgeCron six-field Spring cron of the purge of the closed requests, evaluated in UTC (04:00, BR4.15)
 * @param requestsPerHour requests a person may create in one hour (10, NFR2.4); the next is 429 RATE_LIMITED
 * @param responseTime the due date of a request after its reception (15 days, BR4.1)
 * @param verificationTtl the validity of a verification message (24 hours, BR4.11)
 * @param retentionMonths months a closed request is kept (12, BR4.15)
 * @param batchSize rows per purge batch (1 to 1000, BR4.15)
 * @param timersBatchSize rows per batch of the timers job (1 to 1000; 500, NFR3.4)
 * @param timersLockAtMostFor ShedLock upper bound of the timers job (5 minutes, NFR3.7)
 * @param timersLockAtLeastFor ShedLock lower bound of the timers job (1 minute, NFR3.7)
 * @param purgeLockAtMostFor ShedLock upper bound of the purge (35 minutes, NFR3.7)
 * @param purgeLockAtLeastFor ShedLock lower bound of the purge (5 minutes, NFR3.7)
 * @param recoverAfter a purge without a success for longer than this runs when the api is ready (24 hours, NFR7.1)
 * @param degradedAfter the purge health is DEGRADED after this long without a success (48 hours, NFR7.1)
 * @param countsRefreshInterval the interval of the cached counts of the gauges and of the health (60 s, NFR8.1)
 * @param dueSoon an open request due within this is "due soon" (3 days, BR4.16)
 * @param adminPageSize items of a page of the administrator list (50, NFR3.2)
 * @param adminPageMax the largest page the administrator may ask for (100)
 */
@ConfigurationProperties(prefix = "memetrade.privacy")
public record PrivacyProperties(
        String timersCron,
        String purgeCron,
        int requestsPerHour,
        Duration responseTime,
        Duration verificationTtl,
        int retentionMonths,
        int batchSize,
        int timersBatchSize,
        Duration timersLockAtMostFor,
        Duration timersLockAtLeastFor,
        Duration purgeLockAtMostFor,
        Duration purgeLockAtLeastFor,
        Duration recoverAfter,
        Duration degradedAfter,
        Duration countsRefreshInterval,
        Duration dueSoon,
        int adminPageSize,
        int adminPageMax) {

    public static final int MAX_BATCH_SIZE = 1000;

    public PrivacyProperties {
        timersCron = blankToDefault(timersCron, "0 */15 * * * *");
        purgeCron = blankToDefault(purgeCron, "0 0 4 * * *");
        requestsPerHour = requestsPerHour == 0 ? 10 : requestsPerHour;
        responseTime = responseTime == null ? Duration.ofDays(15) : responseTime;
        verificationTtl = verificationTtl == null ? Duration.ofHours(24) : verificationTtl;
        retentionMonths = retentionMonths == 0 ? 12 : retentionMonths;
        batchSize = batchSize == 0 ? MAX_BATCH_SIZE : batchSize;
        timersBatchSize = timersBatchSize == 0 ? 500 : timersBatchSize;
        timersLockAtMostFor = timersLockAtMostFor == null ? Duration.ofMinutes(5) : timersLockAtMostFor;
        timersLockAtLeastFor = timersLockAtLeastFor == null ? Duration.ofMinutes(1) : timersLockAtLeastFor;
        purgeLockAtMostFor = purgeLockAtMostFor == null ? Duration.ofMinutes(35) : purgeLockAtMostFor;
        purgeLockAtLeastFor = purgeLockAtLeastFor == null ? Duration.ofMinutes(5) : purgeLockAtLeastFor;
        recoverAfter = recoverAfter == null ? Duration.ofHours(24) : recoverAfter;
        degradedAfter = degradedAfter == null ? Duration.ofHours(48) : degradedAfter;
        countsRefreshInterval = countsRefreshInterval == null ? Duration.ofSeconds(60) : countsRefreshInterval;
        dueSoon = dueSoon == null ? Duration.ofDays(3) : dueSoon;
        adminPageSize = adminPageSize == 0 ? 50 : adminPageSize;
        adminPageMax = adminPageMax == 0 ? 100 : adminPageMax;
        if (requestsPerHour < 1) {
            throw new IllegalArgumentException("memetrade.privacy.requests-per-hour must be positive");
        }
        if (retentionMonths < 1) {
            throw new IllegalArgumentException("memetrade.privacy.retention-months must be positive");
        }
        requireBatch(batchSize, "batch-size");
        requireBatch(timersBatchSize, "timers-batch-size");
        requirePositive(responseTime, "response-time");
        requirePositive(verificationTtl, "verification-ttl");
        requirePositive(timersLockAtMostFor, "timers-lock-at-most-for");
        requirePositive(purgeLockAtMostFor, "purge-lock-at-most-for");
        requirePositive(recoverAfter, "recover-after");
        requirePositive(degradedAfter, "degraded-after");
        requirePositive(countsRefreshInterval, "counts-refresh-interval");
        requirePositive(dueSoon, "due-soon");
        requireLockBounds(timersLockAtLeastFor, timersLockAtMostFor, "timers-lock-at-least-for");
        requireLockBounds(purgeLockAtLeastFor, purgeLockAtMostFor, "purge-lock-at-least-for");
        if (adminPageSize < 1 || adminPageMax < adminPageSize) {
            throw new IllegalArgumentException(
                    "memetrade.privacy.admin-page-size must be positive and at most admin-page-max");
        }
    }

    /** The values of application.yml. */
    public static PrivacyProperties defaults() {
        return new PrivacyProperties(
                null, null, 0, null, null, 0, 0, 0, null, null, null, null, null, null, null, null, 0, 0);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void requireBatch(int value, String name) {
        if (value < 1 || value > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("memetrade.privacy." + name + " must be between 1 and 1000");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("memetrade.privacy." + name + " must be a positive duration");
        }
    }

    private static void requireLockBounds(Duration atLeast, Duration atMost, String name) {
        if (atLeast.isNegative() || atLeast.compareTo(atMost) > 0) {
            throw new IllegalArgumentException(
                    "memetrade.privacy." + name + " must be between zero and the matching lock-at-most-for");
        }
    }
}
