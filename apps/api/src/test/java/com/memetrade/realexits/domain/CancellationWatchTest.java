package com.memetrade.realexits.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 4 of the U7, written BEFORE the code, from BR6.4, BR6.7, S38 and U7-NFR4.11: {@code needsUserCancellation} is
 * true with exactly 15 minutes in CANCELLING and false before, with the injected clock.
 */
@Tag("u7-exits-and-fiscal")
class CancellationWatchTest {

    private static final Instant SINCE = Instant.parse("2026-11-17T10:00:00Z");

    @Test
    void fourteenMinutesAndFiftyNineSecondsInCancellingIsStillFalse() {
        Instant now = SINCE.plus(Duration.ofMinutes(14)).plusSeconds(59);

        assertThat(CancellationWatch.needsUserCancellation(ConditionalState.CANCELLING, Optional.of(SINCE), now))
                .isFalse();
    }

    @Test
    void exactlyFifteenMinutesInCancellingIsTrue() {
        Instant now = SINCE.plus(Duration.ofMinutes(15));

        assertThat(CancellationWatch.needsUserCancellation(ConditionalState.CANCELLING, Optional.of(SINCE), now))
                .isTrue();
        assertThat(CancellationWatch.needsUserCancellation(
                        ConditionalState.CANCELLING, Optional.of(SINCE), now.plusSeconds(3600)))
                .isTrue();
    }

    @Test
    void onlyCancellingCanNeedTheSupport() {
        Instant now = SINCE.plus(Duration.ofHours(1));

        for (ConditionalState state : ConditionalState.values()) {
            if (state != ConditionalState.CANCELLING) {
                assertThat(CancellationWatch.needsUserCancellation(state, Optional.of(SINCE), now))
                        .as(state.name())
                        .isFalse();
            }
        }
        assertThat(CancellationWatch.needsUserCancellation(ConditionalState.CANCELLING, Optional.empty(), now))
                .isFalse();
    }

    @Test
    void theAgeOfTheOldestCancellationIsMeasuredInWholeSeconds() {
        assertThat(CancellationWatch.secondsSince(SINCE, SINCE.plusMillis(90_999)))
                .isEqualTo(90);
        assertThat(CancellationWatch.secondsSince(SINCE, SINCE.minusSeconds(5)))
                .as("a clock behind the row never gives a negative age")
                .isZero();
    }
}
