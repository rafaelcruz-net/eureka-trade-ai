package com.memetrade.riskcontrol.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 29 of U6 (volta 3, F10, choice B1; NFR2.5), WRITTEN BEFORE THE CHANGE: the duration of a purge batch is logged
 * and checked in whole milliseconds ROUNDED UP, so a batch of 100 ms and 1 microsecond reads 101 ms and fails the limit
 * of 100 ms; until loop 3 {@code Duration.toMillis()} truncated it to 100 ms and it passed.
 */
@Tag("u6")
class PurgeBatchDurationTest {

    @Test
    void oneMicrosecondAbove100MillisecondsIsLoggedAs101() {
        assertThat(RiskEvaluationPurgeJob.roundedUpMillis(Duration.ofMillis(100).plusNanos(1_000)))
                .isEqualTo(101);
    }

    @Test
    void evenOneNanosecondAboveAWholeMillisecondCountsAsTheNextOne() {
        assertThat(RiskEvaluationPurgeJob.roundedUpMillis(Duration.ofMillis(99).plusNanos(1)))
                .isEqualTo(100);
        assertThat(RiskEvaluationPurgeJob.roundedUpMillis(Duration.ofNanos(100_900_000)))
                .isEqualTo(101);
    }

    @Test
    void aWholeNumberOfMillisecondsStaysAsItIs() {
        assertThat(RiskEvaluationPurgeJob.roundedUpMillis(Duration.ofMillis(100)))
                .isEqualTo(100);
        assertThat(RiskEvaluationPurgeJob.roundedUpMillis(Duration.ZERO)).isZero();
    }
}
