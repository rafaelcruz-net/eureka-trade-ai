package com.memetrade.audit.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Retention of the trail and of the delivered outbox rows (entities.md, BR3.1, BR3.4). Same for every tenant.
 *
 * @param retentionMonths months an event stays in the trail, counted from {@code occurredAt}; never below 12
 * @param outboxDeliveredRetention how long a delivered outbox row is kept (7 days)
 */
public record AuditRetentionPolicy(int retentionMonths, Duration outboxDeliveredRetention) {

    public static final int MIN_RETENTION_MONTHS = 12;

    public AuditRetentionPolicy {
        if (retentionMonths < MIN_RETENTION_MONTHS) {
            throw new IllegalArgumentException("Audit retention must be at least 12 months (BR3.1)");
        }
        Objects.requireNonNull(outboxDeliveredRetention, "outboxDeliveredRetention");
        if (outboxDeliveredRetention.isZero() || outboxDeliveredRetention.isNegative()) {
            throw new IllegalArgumentException("The delivered outbox retention must be positive");
        }
    }

    /**
     * Retention cutoff (BR3.1): start of the UTC day of {@code now} minus {@link #retentionMonths}. Events with
     * {@code occurredAt} strictly before it may be deleted. Independent of the clock's zone.
     */
    public Instant retentionCutoff(Instant now) {
        return now.atZone(ZoneOffset.UTC)
                .toLocalDate()
                .atStartOfDay(ZoneOffset.UTC)
                .minusMonths(retentionMonths)
                .toInstant();
    }

    /** Outbox cutoff (BR3.4): delivered rows with {@code deliveredAt} strictly before it may be deleted. */
    public Instant outboxCutoff(Instant now) {
        return now.minus(outboxDeliveredRetention);
    }
}
