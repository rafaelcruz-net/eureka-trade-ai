package com.memetrade.privacy.domain.request;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * NFR2.4: the limit of requests per person and hour. Called inside the transaction of the creation, after the
 * idempotency claim, so the count and the insert are atomic across instances.
 */
public interface DataRequestRateLimit {

    /**
     * @return empty when the person may create one more request now; otherwise how long until the oldest request of
     *     the window leaves it (at least one second)
     */
    Optional<Duration> refuse(UUID tenantId, UUID userId, Instant now);
}
