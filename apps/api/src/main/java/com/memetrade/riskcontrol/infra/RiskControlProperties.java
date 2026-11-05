package com.memetrade.riskcontrol.infra;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fixed settings of U6 (infrastructure-specification.md, "Configuração da API"): they live only in
 * {@code application.yml}, never in an environment variable, so daily use cannot change risk rules.
 *
 * @param freshnessMaxAge platform freshness limit of price and liquidity (BR2.6, 60 s)
 * @param purge the daily purge of the old approved evaluations (BR4.3 as amended by the NFR Design)
 */
@ConfigurationProperties(prefix = "memetrade.riskcontrol")
public record RiskControlProperties(Duration freshnessMaxAge, Purge purge) {

    /** BR2.6: the barrier applies 60 s, never the 5 minutes of MarketData. */
    public static final Duration FRESHNESS_MAX_AGE = Duration.ofSeconds(60);

    /** Age the {@code order_evaluation} trigger of migration 0009 requires before a DELETE (security-design.md). */
    public static final Duration TRIGGER_AGE = Duration.ofDays(30);

    /** NFR2.5: at most 5000 rows per transaction. */
    public static final int MAX_BATCH_SIZE = 5000;

    public RiskControlProperties {
        Objects.requireNonNull(purge, "memetrade.riskcontrol.purge");
        if (!FRESHNESS_MAX_AGE.equals(freshnessMaxAge)) {
            throw new IllegalArgumentException("memetrade.riskcontrol.freshness-max-age is fixed at 60s (BR2.6)");
        }
    }

    /**
     * @param cron six-field Spring cron, evaluated in UTC
     * @param batchSize rows per DELETE transaction (1 to 5000, NFR2.5)
     * @param maxRun the round stops starting batches after this time (NFR10.6)
     * @param lockAtMostFor ShedLock {@code riskcontrol-purge} upper bound, longer than {@code maxRun}
     * @param selectionAge rows older than this are selected (31 days)
     * @param triggerAge the age the database trigger enforces (30 days); the selection keeps one day of margin
     */
    public record Purge(
            String cron,
            int batchSize,
            Duration maxRun,
            Duration lockAtMostFor,
            Duration selectionAge,
            Duration triggerAge) {

        public Purge {
            if (cron == null || cron.isBlank()) {
                throw new IllegalArgumentException("memetrade.riskcontrol.purge.cron is required");
            }
            if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
                throw new IllegalArgumentException("memetrade.riskcontrol.purge.batch-size must be between 1 and 5000");
            }
            requirePositive(maxRun, "memetrade.riskcontrol.purge.max-run");
            requirePositive(lockAtMostFor, "memetrade.riskcontrol.purge.lock-at-most-for");
            if (lockAtMostFor.compareTo(maxRun) <= 0) {
                throw new IllegalArgumentException(
                        "memetrade.riskcontrol.purge.lock-at-most-for must be longer than max-run");
            }
            if (!TRIGGER_AGE.equals(triggerAge)) {
                throw new IllegalArgumentException(
                        "memetrade.riskcontrol.purge.trigger-age is the constant of the database trigger (30d)");
            }
            requirePositive(selectionAge, "memetrade.riskcontrol.purge.selection-age");
            if (selectionAge.compareTo(triggerAge) <= 0) {
                throw new IllegalArgumentException(
                        "memetrade.riskcontrol.purge.selection-age must be older than trigger-age");
            }
        }
    }

    /** The values shipped in {@code application.yml}; used by unit tests and documentation. */
    public static RiskControlProperties defaults() {
        return new RiskControlProperties(
                FRESHNESS_MAX_AGE,
                new Purge(
                        "0 30 3 * * *",
                        1000,
                        Duration.ofMinutes(30),
                        Duration.ofMinutes(45),
                        Duration.ofDays(31),
                        TRIGGER_AGE));
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
