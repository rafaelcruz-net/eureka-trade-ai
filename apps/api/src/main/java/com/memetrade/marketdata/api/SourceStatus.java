package com.memetrade.marketdata.api;

import java.time.Instant;
import java.util.Optional;

/**
 * Health of a source (BR1.5): PENDING until its first collection, then OK, DEGRADED or FAILED after the last one,
 * with the time of its last successful collection (empty before the first).
 */
public record SourceStatus(SourceRef source, State status, Optional<Instant> lastUpdatedAt) {

    public enum State {
        PENDING,
        OK,
        DEGRADED,
        FAILED;

        /** OK and DEGRADED sources take part in the list and in the assessment (BR1.3, BR1.4). */
        public boolean isAvailable() {
            return this == OK || this == DEGRADED;
        }
    }
}
