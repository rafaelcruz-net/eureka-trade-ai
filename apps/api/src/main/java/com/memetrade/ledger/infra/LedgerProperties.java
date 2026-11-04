package com.memetrade.ledger.infra;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fixed settings of U7 (infrastructure-specification.md): they live only in {@code application.yml}, never in an
 * environment variable, so daily use cannot change integrity rules.
 *
 * @param markingFallbackInterval fallback round of the marking and the pending close when no collection arrives (60 s)
 * @param reconciliationInterval scheduled reconciliation per person (BR8.1, 5 min)
 * @param reconciliationRunLimitPerMinute "check now" requests per person in 60 s (BR8.3, NFR6.5, 6)
 * @param lockTimeout {@code lock_timeout} of the execution inside the cycle transaction (NFR10.8, 2 s)
 * @param statementTimeout {@code statement_timeout} of the execution inside the cycle transaction (NFR10.8, 5 s)
 * @param jobLockTimeout {@code lock_timeout} of each account handled by the marking and the pending close (1 s)
 */
@ConfigurationProperties(prefix = "memetrade.ledger")
public record LedgerProperties(
        Duration markingFallbackInterval,
        Duration reconciliationInterval,
        int reconciliationRunLimitPerMinute,
        Duration lockTimeout,
        Duration statementTimeout,
        Duration jobLockTimeout) {

    public LedgerProperties {
        requirePositive(markingFallbackInterval, "memetrade.ledger.marking-fallback-interval");
        requirePositive(reconciliationInterval, "memetrade.ledger.reconciliation-interval");
        requirePositive(lockTimeout, "memetrade.ledger.lock-timeout");
        requirePositive(statementTimeout, "memetrade.ledger.statement-timeout");
        requirePositive(jobLockTimeout, "memetrade.ledger.job-lock-timeout");
        if (reconciliationRunLimitPerMinute < 1) {
            throw new IllegalArgumentException("memetrade.ledger.reconciliation-run-limit-per-minute must be positive");
        }
    }

    /** The values shipped in {@code application.yml}; used by unit tests and documentation. */
    public static LedgerProperties defaults() {
        return new LedgerProperties(
                Duration.ofSeconds(60),
                Duration.ofMinutes(5),
                6,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(1));
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
