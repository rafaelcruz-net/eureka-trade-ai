package com.memetrade.botwallet.opsfixture;

import com.memetrade.ledger.api.LedgerReadModel;

/** TEST-ONLY: a reconciliation class reading the PAPER ledger, which RealOpsArchitectureTest must refuse. */
public final class DivergencePaperFixture {

    public Object paper(LedgerReadModel paper) {
        return paper;
    }
}
