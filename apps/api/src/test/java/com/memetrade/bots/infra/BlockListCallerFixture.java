package com.memetrade.bots.infra;

import com.memetrade.riskcontrol.api.RealRiskGate;
import java.util.UUID;

/**
 * Test-only sample for {@code RealRiskArchitectureTest}: a class outside the administrator area that changes the block
 * list of the REAL barrier. The rule of U3-NFR6.2 must refuse it. Never part of the production classes.
 */
public final class BlockListCallerFixture {

    private BlockListCallerFixture() {}

    public static void block(RealRiskGate gate, String mint) {
        gate.blockToken("solana", mint, "fixture", UUID.randomUUID());
    }
}
