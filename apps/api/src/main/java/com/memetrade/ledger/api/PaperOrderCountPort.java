package com.memetrade.ledger.api;

import java.util.UUID;

/**
 * J4 (contract-summary.md, u4-data-requests): the number of PAPER orders of a person, for the Access summary of the
 * Privacy module (BR4.8). Read only: nothing in the ledger changes, and REAL never exists here.
 */
public interface PaperOrderCountPort {

    /** Every PAPER order of the person in that tenant, in any status; 0 when there is none. */
    long countPaperOrders(UUID tenantId, UUID ownerUserId);
}
