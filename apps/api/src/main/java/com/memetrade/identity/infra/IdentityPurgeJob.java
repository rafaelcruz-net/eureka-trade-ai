package com.memetrade.identity.infra;

import com.memetrade.identity.domain.purge.IdentityPurge;
import java.time.Instant;
import java.util.Optional;

/**
 * One Identity purge as run by {@link IdentityJobRunner}: its cutoff and one batch of its deletion. The runner holds
 * the lock, loops over the batches (each in a transaction of its own), records the run with its event, logs and
 * measures; the purge only knows its rule and its tables.
 */
public interface IdentityPurgeJob {

    IdentityPurge purge();

    /** Rows whose instant is before or equal to the returned cutoff may be deleted. */
    Instant cutoff(Instant now);

    /**
     * Deletes at most {@code limit} rows of the main table (and their dependent rows first) whose instant is before or
     * equal to {@code cutoff}. Called inside the transaction of the batch.
     */
    BatchCount deleteBatch(Instant cutoff, int limit);

    /** Detail key of the dependent rows in the audit event (for example {@code digestsDeleted}); empty without them. */
    default Optional<String> relatedDetail() {
        return Optional.empty();
    }

    /**
     * Rows deleted by one batch.
     *
     * @param rows rows of the main table; a batch shorter than its limit ends the run
     * @param related dependent rows deleted before them in the same transaction
     */
    record BatchCount(int rows, int related) {}
}
