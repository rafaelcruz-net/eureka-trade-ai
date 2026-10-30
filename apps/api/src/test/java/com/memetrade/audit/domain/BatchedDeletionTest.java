package com.memetrade.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.audit.domain.BatchedDeletion.Outcome;
import com.memetrade.testsupport.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** BR3.2, NFR4.3, NFR4.4: batches of at most N rows, stop at the first failure or at the time limit. */
@Tag("u2")
class BatchedDeletionTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void itDeletesInBatchesUntilABatchComesBackShort() {
        List<Integer> limits = new ArrayList<>();
        int[] remaining = {2500};

        Outcome outcome = new BatchedDeletion(1000, Duration.ofMinutes(30), clock).run(limit -> {
            limits.add(limit);
            int deleted = Math.min(limit, remaining[0]);
            remaining[0] -= deleted;
            return deleted;
        });

        assertThat(outcome.result()).isEqualTo(AuditJobResult.COMPLETED);
        assertThat(outcome.deleted()).isEqualTo(2500);
        assertThat(limits).containsExactly(1000, 1000, 1000);
        assertThat(outcome.failure()).isEmpty();
    }

    @Test
    void anExactMultipleEndsWithOneEmptyBatch() {
        int[] remaining = {2000};
        int[] calls = {0};

        Outcome outcome = new BatchedDeletion(1000, Duration.ofMinutes(30), clock).run(limit -> {
            calls[0]++;
            int deleted = Math.min(limit, remaining[0]);
            remaining[0] -= deleted;
            return deleted;
        });

        assertThat(outcome.result()).isEqualTo(AuditJobResult.COMPLETED);
        assertThat(outcome.deleted()).isEqualTo(2000);
        assertThat(calls[0]).isEqualTo(3);
    }

    @Test
    void nothingToDeleteCompletesWithZero() {
        Outcome outcome = new BatchedDeletion(1000, Duration.ofMinutes(30), clock).run(limit -> 0);

        assertThat(outcome.result()).isEqualTo(AuditJobResult.COMPLETED);
        assertThat(outcome.deleted()).isZero();
    }

    @Test
    void aFailedBatchStopsTheRunAndKeepsWhatEarlierBatchesDeleted() {
        DataAccessResourceFailureException down = new DataAccessResourceFailureException("down");
        int[] calls = {0};

        Outcome outcome = new BatchedDeletion(1000, Duration.ofMinutes(30), clock).run(limit -> {
            if (++calls[0] == 3) {
                throw down;
            }
            return limit;
        });

        assertThat(outcome.result()).isEqualTo(AuditJobResult.FAILED);
        assertThat(outcome.deleted()).isEqualTo(2000);
        assertThat(outcome.failure()).containsSame(down);
        assertThat(calls[0]).isEqualTo(3);
    }

    @Test
    void theTimeLimitStopsBeforeANewBatch() {
        int[] calls = {0};

        Outcome outcome = new BatchedDeletion(1000, Duration.ofMinutes(30), clock).run(limit -> {
            calls[0]++;
            clock.advance(Duration.ofMinutes(10));
            return limit;
        });

        assertThat(outcome.result()).isEqualTo(AuditJobResult.TIME_LIMIT);
        assertThat(calls[0]).isEqualTo(3);
        assertThat(outcome.deleted()).isEqualTo(3000);
    }

    @Test
    void aBatchStartedJustBeforeTheLimitStillRuns() {
        int[] calls = {0};

        Outcome outcome = new BatchedDeletion(1000, Duration.ofMinutes(30), clock).run(limit -> {
            calls[0]++;
            clock.advance(Duration.ofMinutes(30).minusSeconds(1));
            return calls[0] == 1 ? limit : 5;
        });

        assertThat(outcome.result()).isEqualTo(AuditJobResult.COMPLETED);
        assertThat(outcome.deleted()).isEqualTo(1005);
    }

    @Test
    void theBatchSizeMustBePositive() {
        assertThatThrownBy(() -> new BatchedDeletion(0, Duration.ofMinutes(30), clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
