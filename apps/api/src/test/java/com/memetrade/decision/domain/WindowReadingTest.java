package com.memetrade.decision.domain;

import static com.memetrade.decision.domain.DecisionScenarios.LAST_BUCKET;
import static com.memetrade.decision.domain.DecisionScenarios.NOW;
import static com.memetrade.decision.domain.DecisionScenarios.SLOT;
import static com.memetrade.decision.domain.DecisionScenarios.TOKEN_A;
import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.PriceVolumeWindow;
import com.memetrade.riskassessment.api.AssessmentResult;
import com.memetrade.riskassessment.api.RiskBand;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Build and Test, loop 2, Passo 30 (BR4.1; NFR2.2, NFR2.3), written BEFORE the change of {@link WindowReading}: the age
 * of the last closed bucket {@code b_n} is measured from the END of the bucket ({@code bucketStart + 5 min}), not from
 * the {@code observedAt} of its sample. U4 keeps the FIRST observation of each 5-minute bucket, so the sample of
 * {@code b_n} is usually observed at its start, 6 minutes before the cycle at {@code slotStart + 60 s}. {@code b_n}
 * counts when it ended at most 5 minutes (inclusive) before the cycle; the limit MAX_AGE does not change. The reference
 * price keeps the {@code observedAt} reading ({@link WindowReading#fresh}) for the buy, the exits and the order price.
 */
@Tag("u5")
class WindowReadingTest {

    private static final int DEPTH = 3;

    private final DecisionEngine engine = new DecisionEngine();

    /** The sample of every bucket observed at the start of the bucket, as the store of U4 keeps it. */
    private static PriceVolumeWindow observedAtBucketStart(String... prices) {
        List<PriceVolumeWindow.Point> points = new ArrayList<>();
        for (int i = 0; i < prices.length; i++) {
            Instant bucket = LAST_BUCKET.minus(Duration.ofMinutes(5L * (prices.length - 1 - i)));
            points.add(new PriceVolumeWindow.Point(
                    bucket, DecisionScenarios.known(prices[i], bucket), DecisionScenarios.known("1000000", bucket)));
        }
        return new PriceVolumeWindow(TOKEN_A, points.getFirst().bucketStart(), LAST_BUCKET, points);
    }

    @Test
    void aSampleObservedAtTheStartOfTheBucketIsFreshAtTheCycleOneMinuteAfterTheSlot() {
        WindowReading reading = new WindowReading(observedAtBucketStart("1.00", "1.01", "1.01", "1.02"), SLOT);

        assertThat(reading.check(DEPTH, true, NOW)).isEmpty();
    }

    @Test
    void aCycleMoreThanFiveMinutesAfterTheEndOfTheLastBucketIsStaleData() {
        PriceVolumeWindow window =
                DecisionScenarios.window(TOKEN_A, new String[] {"1.00", "1.01", "1.01", "1.02"}, new String[] {
                    "1000000", "1000000", "1000000", "1200000"
                });
        WindowReading reading = new WindowReading(window, SLOT);
        Instant lateCycle = LAST_BUCKET
                .plus(WindowReading.BUCKET)
                .plus(WindowReading.MAX_AGE)
                .plusSeconds(1);

        assertThat(reading.check(DEPTH, true, lateCycle)).contains(NoTradeReason.STALE_DATA);
    }

    @Test
    void exactlyFiveMinutesAfterTheEndOfTheLastBucketIsStillFreshBecauseTheLimitIsInclusive() {
        WindowReading reading = new WindowReading(observedAtBucketStart("1.00", "1.01", "1.01", "1.02"), SLOT);
        Instant boundary = LAST_BUCKET.plus(WindowReading.BUCKET).plus(WindowReading.MAX_AGE);

        assertThat(reading.check(DEPTH, true, boundary)).isEmpty();
    }

    @Test
    void theDataReasonsKeepTheOrderOfBr41BeforeTheAgeOfTheLastBucket() {
        PriceVolumeWindow shortWindow = observedAtBucketStart("1.01", "1.01", "1.02");
        Instant lateCycle = LAST_BUCKET
                .plus(WindowReading.BUCKET)
                .plus(WindowReading.MAX_AGE)
                .plusSeconds(1);

        assertThat(new WindowReading(shortWindow, SLOT).check(DEPTH, true, lateCycle))
                .contains(NoTradeReason.INSUFFICIENT_HISTORY);
    }

    /** D1 with the samples of U4 at the start of each bucket: the momentum signal buys (before: STALE_DATA). */
    @Test
    void theMomentumBuyOfD1HappensWithTheSamplesObservedAtTheStartOfEachBucket() {
        PriceVolumeWindow window = withVolumes(
                observedAtBucketStart("1.00", "1.01", "1.01", "1.02"), "1000000", "1000000", "1000000", "1200000");
        DecisionOutcome outcome = engine.decide(DecisionScenarios.momentum()
                .token(
                        TOKEN_A,
                        window,
                        DecisionScenarios.known("1.02"),
                        new AssessmentResult.Available(20, RiskBand.LOW, 1))
                .build());

        assertThat(outcome.action()).isEqualTo(DecisionAction.BUY);
        assertThat(outcome.noTradeReason()).isEmpty();
    }

    /** BR4.1: a reference price observed more than 5 minutes ago blocks the buy even with the window in time. */
    @Test
    void aStaleReferencePriceWithTheWindowInTimeStillBlocksTheBuy() {
        PriceVolumeWindow window =
                DecisionScenarios.window(TOKEN_A, new String[] {"1.00", "1.01", "1.01", "1.02"}, new String[] {
                    "1000000", "1000000", "1000000", "1200000"
                });
        DecisionOutcome outcome = engine.decide(DecisionScenarios.momentum()
                .token(TOKEN_A, window, staleReference("1.02"), new AssessmentResult.Available(20, RiskBand.LOW, 1))
                .build());

        assertThat(outcome.action()).isEqualTo(DecisionAction.NO_TRADE);
        assertThat(outcome.noTradeReason()).contains(NoTradeReason.STALE_DATA);
    }

    /** BR4.5: a stale reference price with the window in time evaluates no exit; the position stays (HOLD). */
    @Test
    void aStaleReferencePriceWithTheWindowInTimeStillBlocksTheExit() {
        DecisionOutcome outcome = engine.decide(DecisionScenarios.momentum()
                .token(
                        TOKEN_A,
                        observedAtBucketStart("1.00", "1.00", "1.00", "1.00"),
                        staleReference("2.00"),
                        new AssessmentResult.Available(20, RiskBand.LOW, 1))
                .position(TOKEN_A, "100.000000", "100.000000", 600)
                .build());

        assertThat(outcome.action()).isEqualTo(DecisionAction.HOLD);
        assertThat(outcome.exitReason()).isEmpty();
    }

    private static com.memetrade.marketdata.api.MarketValue staleReference(String value) {
        return DecisionScenarios.known(value, NOW.minus(WindowReading.MAX_AGE).minusSeconds(1));
    }

    private static PriceVolumeWindow withVolumes(PriceVolumeWindow window, String... volumes) {
        List<PriceVolumeWindow.Point> points = new ArrayList<>();
        for (int i = 0; i < volumes.length; i++) {
            PriceVolumeWindow.Point point = window.points().get(i);
            points.add(new PriceVolumeWindow.Point(
                    point.bucketStart(), point.price(), DecisionScenarios.known(volumes[i], point.bucketStart())));
        }
        return new PriceVolumeWindow(window.token(), window.from(), window.to(), points);
    }
}
