package com.memetrade.riskassessment.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.memetrade.marketdata.api.MarketCollectionCompleted;
import com.memetrade.marketdata.api.MarketRiskInputs;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.riskassessment.domain.WeightsV1;
import com.memetrade.testsupport.InMemoryLockProvider;
import com.memetrade.testsupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.transaction.support.TransactionTemplate;

/** Joined triggers, the automatic switch, the lock and the health of the recalculation (BR3.7, NFR9.1). */
@Tag("u4")
class RiskRecalculatorTest {

    private final MutableClock clock = new MutableClock();
    private final MarketRiskInputs market = mock(MarketRiskInputs.class);
    private final JdbcRiskWeightStore weights = mock(JdbcRiskWeightStore.class);
    private final JdbcRiskAssessmentStore assessments = mock(JdbcRiskAssessmentStore.class);
    private final Deque<Runnable> queued = new ArrayDeque<>();
    private final InMemoryLockProvider locks = new InMemoryLockProvider();

    private RiskRecalculator recalculator(boolean automatic) {
        when(weights.active()).thenReturn(WeightsV1.weights());
        when(assessments.stamps()).thenReturn(Map.of());
        return new RiskRecalculator(
                new RiskRecalculator.Settings(
                        market,
                        weights,
                        assessments,
                        mock(TransactionTemplate.class),
                        new DefaultLockingTaskExecutor(locks),
                        queued::add,
                        clock,
                        500,
                        automatic),
                new SimpleMeterRegistry());
    }

    @Test
    void twoTriggersDuringARunGiveExactlyOneMoreRun() {
        RiskRecalculator recalculator = recalculator(true);
        when(market.recentTokens()).thenAnswer(call -> List.of()).thenAnswer(call -> List.of());
        when(market.recentTokens()).thenAnswer(new org.mockito.stubbing.Answer<List<?>>() {
            private int calls;

            @Override
            public List<?> answer(org.mockito.invocation.InvocationOnMock invocation) {
                if (calls++ == 0) {
                    recalculator.requestRecalculation();
                    recalculator.requestRecalculation();
                }
                return List.of();
            }
        });

        recalculator.requestRecalculation();
        recalculator.requestRecalculation();
        assertThat(queued).hasSize(1);
        queued.poll().run();

        verify(market, times(2)).recentTokens();
        assertThat(queued).isEmpty();
    }

    @Test
    void theCollectionEventAndTheActivationTriggerOnlyWhenAutomatic() {
        RiskRecalculator manual = recalculator(false);
        manual.onCollection(new MarketCollectionCompleted("sim-a", SourceStatus.State.OK, clock.instant()));
        manual.onWeightsActivated();
        assertThat(queued).isEmpty();

        RiskRecalculator automatic = recalculator(true);
        automatic.onWeightsActivated();
        assertThat(queued).hasSize(1);
    }

    @Test
    void anotherRunHoldingTheLockSkipsThisOne() {
        RiskRecalculator recalculator = recalculator(false);
        locks.hold("risk-recalculate");

        assertThat(recalculator.recalculateNow()).isEmpty();
        verify(market, times(0)).recentTokens();
    }

    @Test
    void theHealthIsUnknownThenUpThenDegradedAfterFiveMinutesOrAFailure() {
        when(market.recentTokens()).thenReturn(List.of());
        RiskRecalculator recalculator = recalculator(false);
        RiskRecalculationHealth health = new RiskRecalculationHealth(recalculator, clock);
        assertThat(health.health().getStatus()).isEqualTo(Status.UNKNOWN);

        recalculator.recalculateNow();
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertThat(health.health().getStatus().getCode()).isEqualTo("DEGRADED");

        recalculator.recalculateNow();
        when(market.recentTokens()).thenThrow(new IllegalStateException("broken"));
        assertThat(recalculator.recalculateNow())
                .hasValueSatisfying(run -> assertThat(run.failed()).isTrue());
        assertThat(health.health().getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(health.health().getDetails()).containsKey("lastFailureAt");
    }

    @Test
    void aRunWithNothingChangedWritesNothing() {
        when(market.recentTokens()).thenReturn(List.of());
        RiskRecalculator recalculator = recalculator(false);

        assertThat(recalculator.recalculateNow()).hasValueSatisfying(run -> {
            assertThat(run.evaluated()).isZero();
            assertThat(run.rewritten()).isZero();
        });
        verify(assessments, times(0)).write(any());
    }
}
