package com.memetrade.architecture.ownquantity;

import com.memetrade.pnl.api.HoldingsResult;
import com.memetrade.pnl.api.RealBotHoldings;
import java.time.Duration;
import java.util.UUID;

/**
 * 261121-paper-real-unico U4 (test sources only): a class outside the cycle of the worker that reads the own holdings of
 * a bot, as a path of the money (a withdrawal, a liquidation) must never do. Rule R1 of
 * {@code OwnQuantityArchitectureTest} must fail on it.
 */
public final class HoldingsOutsideTheCycleSample {

    private final RealBotHoldings holdings;

    public HoldingsOutsideTheCycleSample(RealBotHoldings holdings) {
        this.holdings = holdings;
    }

    public boolean sellable(UUID tenantId, UUID userId, UUID botId) {
        return holdings.holdings(tenantId, userId, botId, Duration.ofMillis(250)) instanceof HoldingsResult.Available;
    }
}
