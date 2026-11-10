package com.memetrade.privacy.domain.purge;

/** Result of one run of a Privacy purge ({@code privacy_job_run.last_result}). */
public enum PurgeResult {
    /** Every batch ran until one came back short, and the run was recorded with its event. */
    SUCCEEDED,
    /** A batch failed (the earlier batches stay deleted) or the run could not be completed. */
    FAILED
}
