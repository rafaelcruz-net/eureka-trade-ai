package com.memetrade.riskcontrol.real.infra;

import com.memetrade.botwallet.domain.PauseReason;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.riskcontrol.domain.LimitChecks;

/**
 * Test-only sample for {@code RealRiskArchitectureTest}: a class of the REAL barrier that reaches the PAPER barrier, an
 * internal class of the BotWallet and the PAPER execution of the ledger. The boundary rule (U3-NFR8.1) must refuse it.
 * Never part of the production classes.
 */
public final class BoundaryViolationFixture {

    private BoundaryViolationFixture() {}

    public static String reasons(LimitChecks paper, PauseReason internal, PaperExecution execution) {
        return paper + ":" + internal + ":" + execution;
    }
}
