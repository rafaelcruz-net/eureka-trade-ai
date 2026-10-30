package com.memetrade.marketdata.api;

import java.time.Instant;
import java.util.Optional;

/** Health of a source: OK, DEGRADED or FAILED, with the time of its last successful collection. */
public record SourceStatus(SourceRef source, State status, Optional<Instant> lastUpdatedAt) {

    public enum State {
        OK,
        DEGRADED,
        FAILED
    }
}
