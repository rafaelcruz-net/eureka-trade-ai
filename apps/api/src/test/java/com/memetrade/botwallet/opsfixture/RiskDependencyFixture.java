package com.memetrade.botwallet.opsfixture;

import com.memetrade.riskcontrol.api.RealRiskGate;

/** TEST-ONLY: a bot wallet class depending on the risk control (a cycle with M5), which RealOpsArchitectureTest refuses. */
public final class RiskDependencyFixture {

    public Object gate(RealRiskGate gate) {
        return gate;
    }
}
