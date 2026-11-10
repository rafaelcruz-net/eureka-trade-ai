package com.memetrade.privacy.domain.request;

import java.time.Instant;

/** BR4.15: the deletion of the closed requests, used only by the purge. */
public interface DataRequestPurgeStore {

    /**
     * Deletes at most {@code limit} requests closed at or before {@code cutoff}, oldest closure first, with their
     * verifications first; an open request is never deleted.
     */
    PurgeBatch deleteClosedUpTo(Instant cutoff, int limit);

    /**
     * Rows deleted by one batch.
     *
     * @param rows requests deleted
     * @param related verifications deleted with them
     */
    record PurgeBatch(int rows, int related) {
        public PurgeBatch {
            if (rows < 0 || related < 0) {
                throw new IllegalArgumentException("Deleted rows are never negative");
            }
        }
    }
}
