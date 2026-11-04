package com.memetrade.pnl.infra;

import com.memetrade.ledger.api.LedgerJobs;
import com.memetrade.shared.health.HealthStatuses;
import com.memetrade.shared.health.JobRounds;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health component {@code ledgerJobs} (NFR9.1), outside the readiness group: UP in normal operation; DEGRADED (HTTP
 * 200) when the last round of the marking, the pending close or the reconciliation failed, when one of them has not run
 * for more than 3 intervals, or when the last reconciliation found divergences. Never DOWN. The detail carries the
 * instant of the last round of each job and the NUMBER of people with divergences, never a person or a value.
 */
@Component("ledgerJobsHealthIndicator")
public class LedgerJobsHealth implements HealthIndicator {

    static final int LATE_AFTER_INTERVALS = 3;

    private final LedgerJobs ledgerJobs;
    private final EquityMarkingJob marking;
    private final ReconciliationJob reconciliation;
    private final Clock clock;
    private final Duration markingInterval;
    private final Duration reconciliationInterval;
    private final Instant startedAt;

    public LedgerJobsHealth(
            LedgerJobs ledgerJobs,
            EquityMarkingJob marking,
            ReconciliationJob reconciliation,
            Clock clock,
            @Value("${memetrade.ledger.marking-fallback-interval}") Duration markingInterval,
            @Value("${memetrade.ledger.reconciliation-interval}") Duration reconciliationInterval) {
        this.ledgerJobs = ledgerJobs;
        this.marking = marking;
        this.reconciliation = reconciliation;
        this.clock = clock;
        this.markingInterval = markingInterval;
        this.reconciliationInterval = reconciliationInterval;
        this.startedAt = clock.instant();
    }

    @Override
    public Health health() {
        Instant now = clock.instant();
        Map<String, Object> details = new LinkedHashMap<>();
        boolean degraded = false;
        degraded |= check("pendingClose", ledgerJobs.pendingCloseRounds(), markingInterval, now, details);
        degraded |= check("equityMarking", marking.rounds(), markingInterval, now, details);
        degraded |= check("reconciliation", reconciliation.rounds(), reconciliationInterval, now, details);
        int divergent = reconciliation.lastDivergentPeople();
        details.put("peopleWithDivergences", divergent);
        degraded |= divergent > 0;
        return (degraded ? Health.status(HealthStatuses.DEGRADED) : Health.up())
                .withDetails(details)
                .build();
    }

    /** True when the job failed in its last round or is late by more than 3 intervals. */
    private boolean check(String name, JobRounds rounds, Duration interval, Instant now, Map<String, Object> details) {
        rounds.lastRunAt().ifPresent(at -> details.put(name + "LastRunAt", at.toString()));
        Instant reference = rounds.lastRunAt().orElse(startedAt);
        boolean late = Duration.between(reference, now).compareTo(interval.multipliedBy(LATE_AFTER_INTERVALS)) > 0;
        if (late) {
            details.put(name + "Late", true);
        }
        if (rounds.lastFailed()) {
            details.put(name + "LastRoundFailed", true);
        }
        return late || rounds.lastFailed();
    }
}
