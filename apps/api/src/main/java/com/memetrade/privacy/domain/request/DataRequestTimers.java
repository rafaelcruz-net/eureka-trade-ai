package com.memetrade.privacy.domain.request;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The job of 15 minutes of the requests (F4 step 1; BR4.13, BR4.14; NFR3.4, NFR7.2), run under its own lock:
 *
 * <ol>
 *   <li>every PENDING verification past its validity expires and its AWAITING_VERIFICATION request is REFUSED by the
 *       system with {@link #IDENTITY_NOT_CONFIRMED}, one DATA_REQUEST_TRANSITIONED each;
 *   <li>every open request past its due date and never notified gets {@code overdueNotifiedAt} and ONE
 *       DATA_REQUEST_OVERDUE.
 * </ol>
 *
 * Both passes are conditional updates that return the rows they changed, taken in batches until a batch comes back
 * short; an event is published only for a returned row, so running twice (or on two instances) duplicates nothing. The
 * caller gives each batch its own transaction.
 */
public class DataRequestTimers {

    /** BR4.13, AC5.6.5: the reason the person reads. */
    public static final String IDENTITY_NOT_CONFIRMED = "identidade não confirmada";

    private final DataRequestStore store;
    private final DataRequestEvents events;
    private final Clock clock;
    private final int batchSize;

    public DataRequestTimers(DataRequestStore store, DataRequestEvents events, Clock clock, int batchSize) {
        this.store = Objects.requireNonNull(store, "store");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    /** Both passes at the instant of the clock. */
    public Outcome run() {
        Instant now = clock.instant();
        int refused = refuseExpiredVerifications(now);
        int notified = notifyOverdue(now);
        return new Outcome(refused, notified);
    }

    /** BR4.13: one pass of the expired verifications. */
    public int refuseExpiredVerifications(Instant now) {
        return drain(limit -> refuseBatch(now, limit));
    }

    /** BR4.14: one pass of the overdue requests. */
    public int notifyOverdue(Instant now) {
        return drain(limit -> notifyBatch(now, limit));
    }

    /** One batch of expired verifications; public so the caller can give it a transaction of its own. */
    public int refuseBatch(Instant now, int limit) {
        List<DataSubjectRequest> refused = store.refuseExpiredVerifications(now, IDENTITY_NOT_CONFIRMED, limit);
        refused.forEach(
                request -> events.transitioned(request, DataRequestStatus.AWAITING_VERIFICATION, Actor.system()));
        return refused.size();
    }

    /** One batch of overdue requests; public so the caller can give it a transaction of its own. */
    public int notifyBatch(Instant now, int limit) {
        List<DataSubjectRequest> marked = store.markOverdue(now, limit);
        marked.forEach(events::overdue);
        return marked.size();
    }

    public int batchSize() {
        return batchSize;
    }

    private int drain(Function<Integer, Integer> batch) {
        int total = 0;
        while (true) {
            int rows = batch.apply(batchSize);
            total += rows;
            if (rows < batchSize) {
                return total;
            }
        }
    }

    /** The rows each pass changed. */
    public record Outcome(int refused, int notified) {}
}
