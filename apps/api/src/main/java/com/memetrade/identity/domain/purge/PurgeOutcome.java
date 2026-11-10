package com.memetrade.identity.domain.purge;

import java.time.Instant;
import java.util.Objects;

/**
 * Outcome of one run of an Identity purge, returned to the schedule, the recovery and the tests.
 *
 * @param deleted rows of the main table deleted (challenges or sessions), also by the batches before a failure
 * @param related rows of the dependent table deleted with them (the used refresh digests of the sessions; 0 otherwise)
 * @param batches batches attempted, including a failed one
 */
public record PurgeOutcome(
        IdentityPurge purge, PurgeResult result, Instant cutoff, long deleted, long related, int batches) {

    public PurgeOutcome {
        Objects.requireNonNull(purge, "purge");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(cutoff, "cutoff");
    }
}
