package com.memetrade.bots.infra;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The fixed settings of U5 (infrastructure-specification.md "Configuração"), read from {@code application.yml} and
 * never from the environment: risk and cycle rules never change by variable. A value out of its domain stops the
 * process with a message naming the property.
 */
@ConfigurationProperties("memetrade.bots")
public record BotProperties(
        Scheduler scheduler,
        String slotCron,
        int cycleThreads,
        Duration lockTimeout,
        Duration statementTimeout,
        VirtualBalance virtualBalance,
        Duration decisionRetention,
        String decisionPurgeCron,
        int decisionPurgeBatchSize,
        int systemPauseBatchSize,
        Stream stream) {

    public BotProperties {
        Objects.requireNonNull(scheduler, "memetrade.bots.scheduler");
        Objects.requireNonNull(virtualBalance, "memetrade.bots.virtual-balance");
        Objects.requireNonNull(stream, "memetrade.bots.stream");
        require(slotCron != null && !slotCron.isBlank(), "memetrade.bots.slot-cron");
        require(cycleThreads >= 1 && cycleThreads <= 8, "memetrade.bots.cycle-threads");
        require(positive(lockTimeout), "memetrade.bots.lock-timeout");
        require(positive(statementTimeout), "memetrade.bots.statement-timeout");
        require(positive(decisionRetention), "memetrade.bots.decision-retention");
        require(decisionPurgeCron != null && !decisionPurgeCron.isBlank(), "memetrade.bots.decision-purge-cron");
        require(
                decisionPurgeBatchSize >= 1 && decisionPurgeBatchSize <= 5000,
                "memetrade.bots.decision-purge-batch-size");
        require(systemPauseBatchSize >= 1 && systemPauseBatchSize <= 1000, "memetrade.bots.system-pause-batch-size");
    }

    public record Scheduler(boolean enabled) {}

    /** BR1.4 (A4): floor, cap and default of the virtual balance, USDC with 6 places. */
    public record VirtualBalance(BigDecimal min, BigDecimal cap, BigDecimal defaultValue) {
        public VirtualBalance {
            Objects.requireNonNull(min, "memetrade.bots.virtual-balance.min");
            Objects.requireNonNull(cap, "memetrade.bots.virtual-balance.cap");
            if (min.signum() <= 0 || cap.compareTo(min) < 0) {
                throw new IllegalArgumentException("memetrade.bots.virtual-balance must have 0 < min <= cap");
            }
            defaultValue = defaultValue == null ? new BigDecimal("1000.000000") : defaultValue;
        }
    }

    /**
     * BR8.2, NFR3.3: the live channel. U9 (NFR2.1, security-design.md "Teto de canais por pessoa"): at most
     * {@code maxChannelsPerPerson} open channels per person (default 5, between 1 and 20); the next one is refused
     * with {@code 429 RATE_LIMITED} before the stream opens.
     */
    public record Stream(
            Duration pollInterval, Duration heartbeatInterval, Duration timeout, int maxChannelsPerPerson) {
        public static final int MIN_CHANNELS_PER_PERSON = 1;
        public static final int MAX_CHANNELS_PER_PERSON = 20;

        public Stream {
            if (!positive(pollInterval) || !positive(heartbeatInterval) || !positive(timeout)) {
                throw new IllegalArgumentException("memetrade.bots.stream durations must be positive");
            }
            require(
                    maxChannelsPerPerson >= MIN_CHANNELS_PER_PERSON && maxChannelsPerPerson <= MAX_CHANNELS_PER_PERSON,
                    "memetrade.bots.stream.max-channels-per-person");
        }
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }

    private static void require(boolean condition, String property) {
        if (!condition) {
            throw new IllegalArgumentException(property + " is out of its domain");
        }
    }
}
