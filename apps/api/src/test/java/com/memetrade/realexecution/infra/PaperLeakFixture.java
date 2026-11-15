package com.memetrade.realexecution.infra;

import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.ledger.infra.JdbcRealLedger;

/**
 * TEST ONLY: a REAL execution class that uses the PAPER execution and the internals of the ledger (must fail the PAPER
 * rule and the boundary rule).
 */
public final class PaperLeakFixture {

    private final PaperExecution paper;
    private final JdbcRealLedger internals;

    PaperLeakFixture(PaperExecution paper, JdbcRealLedger internals) {
        this.paper = paper;
        this.internals = internals;
    }

    Object paper() {
        return paper;
    }

    Object internals() {
        return internals;
    }
}
