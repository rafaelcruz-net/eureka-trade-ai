package com.memetrade.privacy.domain.purge;

import java.util.Optional;

/** Persistence of the last run of each Privacy purge ({@code privacy_job_run}, NFR7.1). */
public interface PurgeRunStore {

    Optional<PurgeRun> find(PrivacyPurge purge);

    /** Upserts the row of the purge; an empty {@code lastSuccessAt} keeps the success already recorded. */
    void record(PurgeRun run);
}
