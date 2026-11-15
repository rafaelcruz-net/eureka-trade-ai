package com.memetrade.riskcontrol.real.domain;

import com.memetrade.ledger.api.RealBalance;
import com.memetrade.ledger.api.RealFlows;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.riskassessment.api.AssessmentResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 261113-trading-real-bot U3 (logical-components.md RealGateInputs): what a BUY reads after the state and the source,
 * under the wallet lock. Read once by the reader; the policy never reads anything itself.
 *
 * @param assessment the indicator of the scanner for the token (a technical failure arrives as Unavailable)
 * @param blocked the token is on the block list (BR2.2)
 * @param price the quote of the token, for BR3.1
 * @param liquidity the liquidity of the token, for BR3.1
 * @param volume24h the 24 h volume of the token, for BR3.1
 * @param positions every open position with its price in lamports (BR2.4, BR5.2)
 * @param balance the REAL balance from M2, with its reserves by kind (never the PAPER balance)
 * @param openGateReservedLamports the OPEN reservations of the barrier for this wallet (BR5.1)
 * @param approvedBuysToday the approved buys of this bot since 00:00 UTC (BR4.2)
 * @param snapshot the photo of today, when it exists (BR4.4)
 * @param flowsSinceSnapshot deposits and withdrawals after the photo; none without a photo (BR4.3)
 */
public record BuyInputs(
        AssessmentResult assessment,
        boolean blocked,
        MarketValue price,
        MarketValue liquidity,
        MarketValue volume24h,
        List<PricedPosition> positions,
        RealBalance balance,
        long openGateReservedLamports,
        int approvedBuysToday,
        Optional<DaySnapshot> snapshot,
        RealFlows flowsSinceSnapshot) {

    public BuyInputs {
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(liquidity, "liquidity");
        Objects.requireNonNull(volume24h, "volume24h");
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(flowsSinceSnapshot, "flowsSinceSnapshot");
        positions = List.copyOf(positions);
    }
}
