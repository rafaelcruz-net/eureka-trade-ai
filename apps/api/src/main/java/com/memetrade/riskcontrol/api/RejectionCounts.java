package com.memetrade.riskcontrol.api;

import java.time.Instant;

/**
 * U8 addition to the public interface of U6 (functional-spec.md of U8 "Acréscimos a contratos", BR3.2): the number of
 * refused orders of EVERY tenant since an instant, for the system status of the administrator. Only the number: no
 * tenant, person, bot, token or reason ever leaves through this interface.
 */
public interface RejectionCounts {

    /** Rejections with {@code evaluatedAt >= from} across all tenants (PAPER only, as every rejection of the beta). */
    long countRejectedSince(Instant from);
}
