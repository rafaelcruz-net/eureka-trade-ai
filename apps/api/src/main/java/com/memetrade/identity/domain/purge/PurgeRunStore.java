package com.memetrade.identity.domain.purge;

import java.util.Optional;

/** Persistence of the last run of each Identity purge ({@code identity_job_run}, BR2.16). */
public interface PurgeRunStore {

    Optional<PurgeRun> find(IdentityPurge purge);

    /**
     * Upserts the row of the purge; an empty {@code lastSuccessAt} keeps the success already recorded. Runs in a
     * transaction of its own unless the caller already opened one (the run row and its event commit together).
     */
    void record(PurgeRun run);
}
