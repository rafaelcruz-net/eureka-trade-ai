package com.memetrade.botwallet.infra;

import com.memetrade.riskcontrol.api.OrderIntent;
import com.memetrade.riskcontrol.api.RealRiskGate;

/**
 * Test-only sample for {@code RealRiskArchitectureTest}: a BotWallet class (the withdrawal) that asks the REAL barrier.
 * The rule of U3-NFR4.7 must refuse it. Never part of the production classes.
 */
public final class BarrierUsingFixture {

    private BarrierUsingFixture() {}

    public static boolean allowed(RealRiskGate gate, OrderIntent intent) {
        return gate.evaluate(intent).approved();
    }
}
