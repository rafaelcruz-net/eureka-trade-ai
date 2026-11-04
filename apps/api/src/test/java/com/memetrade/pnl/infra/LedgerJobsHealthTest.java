package com.memetrade.pnl.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.memetrade.ledger.api.LedgerJobs;
import com.memetrade.shared.health.HealthStatuses;
import com.memetrade.shared.health.JobRounds;
import com.memetrade.testsupport.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/** Passo 19 (NFR9.1): the health component {@code ledgerJobs} in each condition; never DOWN. */
@Tag("u7")
class LedgerJobsHealthTest {

    private final MutableClock clock = new MutableClock();
    private final JobRounds pending = new JobRounds();
    private final JobRounds marking = new JobRounds();
    private final JobRounds reconciliation = new JobRounds();
    private final ReconciliationJob reconciliationJob = mock(ReconciliationJob.class);
    private LedgerJobsHealth health;

    @BeforeEach
    void wire() {
        EquityMarkingJob markingJob = mock(EquityMarkingJob.class);
        when(markingJob.rounds()).thenReturn(marking);
        when(reconciliationJob.rounds()).thenReturn(reconciliation);
        LedgerJobs ledgerJobs = () -> pending;
        health = new LedgerJobsHealth(
                ledgerJobs, markingJob, reconciliationJob, clock, Duration.ofSeconds(60), Duration.ofMinutes(5));
    }

    private void allRanNow() {
        pending.succeeded(clock.instant());
        marking.succeeded(clock.instant());
        reconciliation.succeeded(clock.instant());
    }

    @Test
    void upWhenEveryJobRanRecentlyWithoutDivergence() {
        allRanNow();

        Health result = health.health();

        assertThat(result.getStatus()).isEqualTo(Status.UP);
        assertThat(result.getDetails())
                .containsEntry("peopleWithDivergences", 0)
                .containsKey("reconciliationLastRunAt");
    }

    @Test
    void upRightAfterStartUpBeforeTheFirstRounds() {
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void degradedWhenALastRoundFailed() {
        allRanNow();
        marking.failed(clock.instant());

        Health result = health.health();

        assertThat(result.getStatus()).isEqualTo(HealthStatuses.DEGRADED);
        assertThat(result.getDetails()).containsEntry("equityMarkingLastRoundFailed", true);
    }

    @Test
    void degradedWhenAJobIsLateByMoreThanThreeIntervals() {
        allRanNow();
        clock.advance(Duration.ofSeconds(181));

        Health result = health.health();

        assertThat(result.getStatus()).isEqualTo(HealthStatuses.DEGRADED);
        assertThat(result.getDetails()).containsEntry("pendingCloseLate", true).doesNotContainKey("reconciliationLate");
    }

    @Test
    void degradedWithTheNumberOfPeopleWithDivergencesAndNeverDown() {
        allRanNow();
        when(reconciliationJob.lastDivergentPeople()).thenReturn(2);

        Health result = health.health();

        assertThat(result.getStatus()).isEqualTo(HealthStatuses.DEGRADED).isNotEqualTo(Status.DOWN);
        assertThat(result.getDetails()).containsEntry("peopleWithDivergences", 2);
    }

    @Test
    void neverRunningAfterThreeIntervalsFromStartUpIsDegraded() {
        clock.advance(Duration.ofMinutes(16));

        assertThat(health.health().getStatus()).isEqualTo(HealthStatuses.DEGRADED);
    }
}
