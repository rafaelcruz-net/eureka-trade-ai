package com.memetrade.feedback.infra;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fixed settings of the feedback of U8 ({@code memetrade.admin.feedback}, BR6.3, BR6.7, NFR2.5, NFR2.6). They live only
 * in {@code application.yml}; daily use cannot change the limit or the retention by variable.
 *
 * @param maxPerHour accepted comments per person in the last hour (10)
 * @param retention age after which a comment is deleted (180 days)
 * @param purgeCron six-field Spring cron of the daily purge, evaluated in UTC (03:00)
 * @param purgeBatchSize rows per DELETE transaction (1000)
 * @param purgeLockAtMostFor ShedLock upper bound of one purge round
 * @param purgeStaleAfter the health indicator is DOWN when the last success is older than this (48 hours)
 * @param pageMax largest page of the administrator list (100)
 */
@ConfigurationProperties(prefix = "memetrade.admin.feedback")
public record FeedbackProperties(
        int maxPerHour,
        Duration retention,
        String purgeCron,
        int purgeBatchSize,
        Duration purgeLockAtMostFor,
        Duration purgeStaleAfter,
        int pageMax) {

    public FeedbackProperties {
        maxPerHour = maxPerHour == 0 ? 10 : maxPerHour;
        retention = retention == null ? Duration.ofDays(180) : retention;
        purgeCron = purgeCron == null || purgeCron.isBlank() ? "0 0 3 * * *" : purgeCron;
        purgeBatchSize = purgeBatchSize == 0 ? 1000 : purgeBatchSize;
        purgeLockAtMostFor = purgeLockAtMostFor == null ? Duration.ofMinutes(30) : purgeLockAtMostFor;
        purgeStaleAfter = purgeStaleAfter == null ? Duration.ofHours(48) : purgeStaleAfter;
        pageMax = pageMax == 0 ? 100 : pageMax;
        if (maxPerHour < 1) {
            throw new IllegalArgumentException("memetrade.admin.feedback.max-per-hour must be positive");
        }
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("memetrade.admin.feedback.retention must be positive");
        }
        if (purgeBatchSize < 1 || purgeBatchSize > 5000) {
            throw new IllegalArgumentException("memetrade.admin.feedback.purge-batch-size must be 1 to 5000");
        }
        if (pageMax < 1 || pageMax > 100) {
            throw new IllegalArgumentException("memetrade.admin.feedback.page-max must be 1 to 100");
        }
    }

    public static FeedbackProperties defaults() {
        return new FeedbackProperties(0, null, null, 0, null, null, 0);
    }
}
