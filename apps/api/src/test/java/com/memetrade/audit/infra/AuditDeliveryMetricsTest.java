package com.memetrade.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.memetrade.testsupport.MutableClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/** NFR4.2, NFR9.2, NFR9.5: delivery histogram from the real commit, unmeasured samples and the UTC daily count. */
@Tag("u2")
@ExtendWith(OutputCaptureExtension.class)
class AuditDeliveryMetricsTest {

    private final JdbcTrailStatistics statistics = mock(JdbcTrailStatistics.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-28T23:59:58Z"));
    private final AuditDeliveryMetrics metrics = new AuditDeliveryMetrics(statistics, clock, registry);

    @Test
    void aDeliveryWithItsCommitInstantIsAHistogramSampleInsideTheFiveSecondBucket() {
        Instant committed = Instant.parse("2026-10-28T12:00:00Z");

        metrics.delivered(Optional.of(committed), committed.plusSeconds(2));

        Timer timer = registry.get("audit_delivery").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(2000.0);
        assertThat(bucket(timer, 5)).isEqualTo(1.0);
        assertThat(bucket(timer, 1)).isZero();
    }

    @Test
    void aDeliveryWithoutCommitInstantIsCountedAsUnmeasured() {
        metrics.delivered(Optional.empty(), Instant.parse("2026-10-28T12:00:00Z"));

        assertThat(registry.get("audit_delivery").timer().count()).isZero();
        assertThat(registry.get("audit_delivery_unmeasured").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aCommitInstantAfterTheRecordIsUnmeasuredNeverANegativeSample() {
        Instant recorded = Instant.parse("2026-10-28T12:00:00Z");

        metrics.delivered(Optional.of(recorded.plusSeconds(1)), recorded);

        assertThat(registry.get("audit_delivery").timer().count()).isZero();
        assertThat(registry.get("audit_delivery_unmeasured").counter().count()).isEqualTo(1.0);
    }

    @Test
    void theDailyCountResetsAtTheUtcDayChange() {
        metrics.recorded(3);
        assertThat(registry.get("audit_events_recorded_today").gauge().value()).isEqualTo(3.0);

        clock.advance(Duration.ofSeconds(2));

        assertThat(metrics.recordedToday()).isZero();
        metrics.recorded(1);
        assertThat(metrics.recordedToday()).isEqualTo(1);
    }

    @Test
    void theStartupCountReadsTheCurrentUtcDayOnly() {
        when(statistics.countRecordedBetween(any(), any())).thenReturn(42L);

        metrics.initializeTodayCount();

        verify(statistics)
                .countRecordedBetween(Instant.parse("2026-10-28T00:00:00Z"), Instant.parse("2026-10-29T00:00:00Z"));
        assertThat(metrics.recordedToday()).isEqualTo(42);
    }

    @Test
    void theUtcDayIsUsedEvenWhenTheClockZoneIsBrasilia() {
        Clock brasilia = Clock.fixed(Instant.parse("2026-10-29T01:00:00Z"), ZoneId.of("America/Sao_Paulo"));
        AuditDeliveryMetrics local = new AuditDeliveryMetrics(statistics, brasilia, new SimpleMeterRegistry());

        local.initializeTodayCount();

        verify(statistics)
                .countRecordedBetween(Instant.parse("2026-10-29T00:00:00Z"), Instant.parse("2026-10-30T00:00:00Z"));
    }

    @Test
    void commitTimestampsOffLogOneErrorNamingTheParameterAndAreRememberedAsOff(CapturedOutput output) {
        when(statistics.trackCommitTimestamp()).thenReturn("off");

        assertThat(metrics.commitTimestampsTracked()).isFalse();
        assertThat(metrics.commitTimestampsTracked()).isFalse();

        verify(statistics, times(1)).trackCommitTimestamp();
        assertThat(output.getAll()).contains("track_commit_timestamp is off");
        assertThat(output.getAll().split("track_commit_timestamp is off", -1)).hasSize(2);
    }

    @Test
    void commitTimestampsOnAreRemembered() {
        when(statistics.trackCommitTimestamp()).thenReturn("on");

        assertThat(metrics.commitTimestampsTracked()).isTrue();
        assertThat(metrics.commitTimestampsTracked()).isTrue();

        verify(statistics, times(1)).trackCommitTimestamp();
    }

    @Test
    void aDatabaseErrorIsNotRememberedSoTheNextCycleAsksAgain() {
        when(statistics.trackCommitTimestamp())
                .thenThrow(new DataAccessResourceFailureException("down"))
                .thenReturn("on");

        assertThat(metrics.commitTimestampsTracked()).isFalse();
        assertThat(metrics.commitTimestampsTracked()).isTrue();
    }

    @Test
    void theStartupHookChecksTheParameterAndSurvivesADatabaseError(CapturedOutput output) {
        when(statistics.trackCommitTimestamp()).thenReturn("on");
        when(statistics.countRecordedBetween(any(), any())).thenThrow(new DataAccessResourceFailureException("down"));

        AuditJobRecovery recovery = mock(AuditJobRecovery.class);
        org.springframework.scheduling.TaskScheduler scheduler =
                mock(org.springframework.scheduling.TaskScheduler.class);
        MutableClock startupClock = new MutableClock();

        new AuditStartup(metrics, recovery, scheduler, startupClock).onReady();

        verify(statistics).trackCommitTimestamp();
        assertThat(output.getAll()).contains("Could not seed audit_events_recorded_today");
        assertThat(metrics.recordedToday()).isZero();
        // The job recovery goes to the scheduler pool (never blocks readiness) and runs the overdue jobs there.
        org.mockito.ArgumentCaptor<Runnable> task = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(task.capture(), org.mockito.ArgumentMatchers.eq(startupClock.instant()));
        org.mockito.Mockito.verifyNoInteractions(recovery);
        task.getValue().run();
        verify(recovery).recoverOverdueJobs();
    }

    private static double bucket(Timer timer, int seconds) {
        return Arrays.stream(timer.takeSnapshot().histogramCounts())
                .filter(b -> b.bucket(TimeUnit.SECONDS) == seconds)
                .mapToDouble(CountAtBucket::count)
                .findFirst()
                .orElseThrow();
    }
}
