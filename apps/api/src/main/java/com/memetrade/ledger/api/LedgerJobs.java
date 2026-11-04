package com.memetrade.ledger.api;

import com.memetrade.shared.health.JobRounds;

/** The rounds of the jobs of the ledger, read by the health indicator {@code ledgerJobs} of the pnl module (NFR9.1). */
public interface LedgerJobs {

    /** The pending close job (F4). */
    JobRounds pendingCloseRounds();
}
