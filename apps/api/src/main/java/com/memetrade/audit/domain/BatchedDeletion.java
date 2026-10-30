package com.memetrade.audit.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Deletion loop shared by the daily jobs (BR3.2, NFR4.3, NFR4.4): each batch is one transaction of at most
 * {@code batchSize} rows; the loop stops at the first failed batch (earlier batches stay deleted), before starting a
 * batch once {@code timeLimit} has passed, or when a batch deletes fewer rows than asked (nothing left).
 */
public final class BatchedDeletion {

    /** One batch: deletes at most {@code limit} rows in its own transaction and returns how many it deleted. */
    @FunctionalInterface
    public interface Batch {
        int delete(int limit);
    }

    /**
     * Outcome of the loop.
     *
     * @param failure the exception of the failed batch, only for {@link AuditJobResult#FAILED}
     */
    public record Outcome(AuditJobResult result, long deleted, Optional<RuntimeException> failure) {
        public Outcome {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(failure, "failure");
        }
    }

    private final int batchSize;
    private final Duration timeLimit;
    private final Clock clock;

    public BatchedDeletion(int batchSize, Duration timeLimit, Clock clock) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.batchSize = batchSize;
        this.timeLimit = Objects.requireNonNull(timeLimit, "timeLimit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Outcome run(Batch batch) {
        Instant started = clock.instant();
        long deleted = 0;
        while (true) {
            if (Duration.between(started, clock.instant()).compareTo(timeLimit) >= 0) {
                return new Outcome(AuditJobResult.TIME_LIMIT, deleted, Optional.empty());
            }
            int rows;
            try {
                rows = batch.delete(batchSize);
            } catch (RuntimeException failed) {
                // Converted into an explicit FAILED result; the job logs it with its cause and records the run.
                return new Outcome(AuditJobResult.FAILED, deleted, Optional.of(failed));
            }
            deleted += rows;
            if (rows < batchSize) {
                return new Outcome(AuditJobResult.COMPLETED, deleted, Optional.empty());
            }
        }
    }
}
