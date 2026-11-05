package com.memetrade.riskcontrol.domain;

import com.memetrade.marketdata.api.MarketValue;
import java.util.Objects;

/**
 * The market data read by one evaluation, recorded with every rejection (entities.md MarketInputs, Q10): price and
 * liquidity exactly as C11 returned them (KNOWN with source and time, or UNKNOWN with the reason), the estimated
 * slippage and the order value, UNKNOWN when they could not be computed (BR3.2). Enough to evaluate the rejection again
 * (NFR8.1).
 */
public record MarketInputs(
        MarketValue referencePrice, MarketValue liquidity, RiskFigure estimatedSlippagePct, RiskFigure orderValue) {

    public MarketInputs {
        Objects.requireNonNull(referencePrice, "referencePrice");
        Objects.requireNonNull(liquidity, "liquidity");
        Objects.requireNonNull(estimatedSlippagePct, "estimatedSlippagePct");
        Objects.requireNonNull(orderValue, "orderValue");
    }
}
