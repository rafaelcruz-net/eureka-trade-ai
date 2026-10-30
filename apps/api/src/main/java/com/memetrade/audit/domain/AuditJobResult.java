package com.memetrade.audit.domain;

/** How a run of a daily audit job ended (BR3.2, NFR4.3, NFR4.4). */
public enum AuditJobResult {
    /** Every row older than the cutoff was deleted. */
    COMPLETED,
    /** A batch failed; earlier batches stay deleted and the next run continues. */
    FAILED,
    /** The run stopped before a new batch because the time limit had passed. */
    TIME_LIMIT
}
