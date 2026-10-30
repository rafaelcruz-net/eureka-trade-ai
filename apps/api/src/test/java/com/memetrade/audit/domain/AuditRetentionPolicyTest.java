package com.memetrade.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** BR3.1 and BR3.4: cutoffs of the purge and of the outbox cleanup, computed in UTC whatever the clock's zone. */
@Tag("u2")
class AuditRetentionPolicyTest {

    private final AuditRetentionPolicy policy = new AuditRetentionPolicy(12, Duration.ofDays(7));

    @Test
    void theRetentionCutoffIsTheStartOfTheUtcDayMinusTwelveMonths() {
        assertThat(policy.retentionCutoff(Instant.parse("2026-10-28T12:00:00Z")))
                .isEqualTo(Instant.parse("2025-10-28T00:00:00Z"));
        assertThat(policy.retentionCutoff(Instant.parse("2026-10-28T00:00:00Z")))
                .isEqualTo(Instant.parse("2025-10-28T00:00:00Z"));
        assertThat(policy.retentionCutoff(Instant.parse("2026-10-28T23:59:59.999Z")))
                .isEqualTo(Instant.parse("2025-10-28T00:00:00Z"));
    }

    @Test
    void theCutoffIsTheSameWithAClockInTheBrasiliaZone() {
        // 22:30 in Brasília on the 28th is already the 29th in UTC: the UTC day counts.
        Clock brasilia = Clock.fixed(Instant.parse("2026-10-29T01:30:00Z"), ZoneId.of("America/Sao_Paulo"));

        assertThat(policy.retentionCutoff(brasilia.instant())).isEqualTo(Instant.parse("2025-10-29T00:00:00Z"));
    }

    @Test
    void theEndOfAMonthIsClampedByTheCalendar() {
        assertThat(policy.retentionCutoff(Instant.parse("2025-02-28T10:00:00Z")))
                .isEqualTo(Instant.parse("2024-02-28T00:00:00Z"));
        assertThat(policy.retentionCutoff(Instant.parse("2024-02-29T10:00:00Z")))
                .isEqualTo(Instant.parse("2023-02-28T00:00:00Z"));
    }

    @Test
    void aLongerRetentionMovesTheCutoffBack() {
        assertThat(new AuditRetentionPolicy(18, Duration.ofDays(7))
                        .retentionCutoff(Instant.parse("2026-10-28T12:00:00Z")))
                .isEqualTo(Instant.parse("2025-04-28T00:00:00Z"));
    }

    @Test
    void theRetentionIsNeverBelowTwelveMonths() {
        assertThatThrownBy(() -> new AuditRetentionPolicy(11, Duration.ofDays(7)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12 months");
    }

    @Test
    void theOutboxCutoffIsSevenDaysBeforeNow() {
        assertThat(policy.outboxCutoff(Instant.parse("2026-10-28T12:00:00Z")))
                .isEqualTo(Instant.parse("2026-10-21T12:00:00Z"));
    }

    @Test
    void theOutboxRetentionMustBePositive() {
        assertThatThrownBy(() -> new AuditRetentionPolicy(12, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditRetentionPolicy(12, null)).isInstanceOf(NullPointerException.class);
    }
}
