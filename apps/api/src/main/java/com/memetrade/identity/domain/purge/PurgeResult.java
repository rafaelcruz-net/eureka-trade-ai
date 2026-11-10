package com.memetrade.identity.domain.purge;

/** Result of one run of an Identity purge (entities.md PurgeRun.lastResult). */
public enum PurgeResult {
    /** Every batch ran until one came back short, and the run was recorded with its event. */
    SUCCEEDED,
    /** A batch failed (the earlier batches stay deleted) or the run could not be completed. */
    FAILED
}
