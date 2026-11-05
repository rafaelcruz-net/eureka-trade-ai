package com.memetrade.riskcontrol.domain;

import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.pnl.api.PortfolioRiskView.RiskSnapshot;
import com.memetrade.riskcontrol.api.BotLimitsVersionRef;
import com.memetrade.riskcontrol.api.CandidateOrder;
import java.time.Instant;
import java.util.Objects;

/**
 * Everything one evaluation reads (BR1.3), as an immutable value: the order, the limits handed by U5, price and
 * liquidity read once from C11, the risk view read once from C13 and "now" from the injectable clock. The checks are a
 * pure function of it, so a recorded rejection can be evaluated again with the same result (NFR8.1, NFR8.2).
 */
public record GateInputs(
        CandidateOrder order,
        BotLimitsVersionRef limits,
        MarketValue referencePrice,
        MarketValue liquidity,
        RiskSnapshot risk,
        Instant now) {

    public GateInputs {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(referencePrice, "referencePrice");
        Objects.requireNonNull(liquidity, "liquidity");
        Objects.requireNonNull(risk, "risk");
        Objects.requireNonNull(now, "now");
    }
}
