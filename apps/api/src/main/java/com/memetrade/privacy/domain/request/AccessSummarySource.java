package com.memetrade.privacy.domain.request;

import java.util.Optional;
import java.util.UUID;

/** BR4.8, NFR1.2: the Access summary of ONE person, assembled from J1 to J4 and her own requests. */
public interface AccessSummarySource {

    /** Empty when the person has no summary any more (unknown in that tenant, or anonymised). */
    Optional<AccessSummary> assemble(UUID tenantId, UUID userId);
}
