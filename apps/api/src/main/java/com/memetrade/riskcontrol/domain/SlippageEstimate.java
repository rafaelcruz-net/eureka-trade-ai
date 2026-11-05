package com.memetrade.riskcontrol.domain;

import com.memetrade.riskcontrol.api.OrderSide;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Copy of the parameters of the simulation policy v1 of U7 (BR1.5, BR1.6; infrastructure-specification.md: fixed in
 * the code, kept equal to the policy in force by {@code SlippageConformanceIT}). Pure: no clock, no database.
 *
 * <ul>
 *   <li>value = money(quantity x price), the price taken with 12 decimals (BR1.4);
 *   <li>consumption% = percent(value / liquidity x 100); slippage% = spread + consumption% x impact, WITHOUT the 10%
 *       cap of U7: the barrier estimates the whole order, which is conservative (BR1.5);
 *   <li>buy cost = money(value x (1 + slippage / 100)) + money(that x fee / 100) (BR1.6);
 *   <li>net exit value of the bought quantity, like the equity measure of U7 (BR6.2 of U7): exit price = price(ref x
 *       (1 - slippage / 100)), floored at zero; gross = money(quantity x exit price); net = gross - money(gross x fee /
 *       100); equity drop of a buy = cost - net; of a sell = 0.
 * </ul>
 *
 * USDC with 6 decimals, unit prices 12, percentage points 4, all {@code HALF_EVEN}; never floating point.
 */
public record SlippageEstimate(BigDecimal spreadPct, BigDecimal impactPerConsumedPct, BigDecimal feePct) {

    static final int MONEY = 6;
    static final int PRICE = 12;
    static final int PERCENT = 4;
    static final BigDecimal HUNDRED = new BigDecimal("100");

    /** A real zero of a USDC amount (the drop of a sell), never a stand-in for an unknown value. */
    static final BigDecimal NO_DROP = new BigDecimal("0.000000");

    /** The values of policy v1 seeded by U7 (spread 0.5000, impact 1.0000 per consumed point, fee 0.3000). */
    public static final SlippageEstimate V1 =
            new SlippageEstimate(new BigDecimal("0.5000"), new BigDecimal("1.0000"), new BigDecimal("0.3000"));

    public SlippageEstimate {
        Objects.requireNonNull(spreadPct, "spreadPct");
        Objects.requireNonNull(impactPerConsumedPct, "impactPerConsumedPct");
        Objects.requireNonNull(feePct, "feePct");
    }

    /**
     * @param referencePrice a usable reference price, above zero
     * @param liquidity a usable liquidity, above zero
     */
    public Estimate estimate(OrderSide side, BigDecimal quantity, BigDecimal referencePrice, BigDecimal liquidity) {
        if (referencePrice.signum() <= 0 || liquidity.signum() <= 0) {
            throw new IllegalArgumentException("The estimate needs a price and a liquidity above zero");
        }
        BigDecimal price = referencePrice.setScale(PRICE, RoundingMode.HALF_EVEN);
        BigDecimal value = money(quantity.multiply(price));
        BigDecimal consumed = value.multiply(HUNDRED).divide(liquidity, PERCENT, RoundingMode.HALF_EVEN);
        BigDecimal slippage = percent(spreadPct.add(consumed.multiply(impactPerConsumedPct)));

        BigDecimal withSlippage = money(value.multiply(BigDecimal.ONE.add(slippage.divide(HUNDRED))));
        BigDecimal fee = fee(withSlippage);
        BigDecimal cost = withSlippage.add(fee);

        BigDecimal exitPrice = price.multiply(BigDecimal.ONE.subtract(slippage.divide(HUNDRED)))
                .setScale(PRICE, RoundingMode.HALF_EVEN);
        if (exitPrice.signum() < 0) {
            exitPrice = new BigDecimal("0.000000000000");
        }
        BigDecimal exitGross = money(quantity.multiply(exitPrice));
        BigDecimal exitNet = exitGross.subtract(fee(exitGross));
        BigDecimal drop = side == OrderSide.BUY ? cost.subtract(exitNet) : NO_DROP;
        return new Estimate(value, consumed, slippage, withSlippage, fee, cost, exitPrice, exitGross, exitNet, drop);
    }

    private BigDecimal fee(BigDecimal gross) {
        return money(gross.multiply(feePct).divide(HUNDRED));
    }

    static BigDecimal money(BigDecimal exact) {
        return exact.setScale(MONEY, RoundingMode.HALF_EVEN);
    }

    static BigDecimal percent(BigDecimal exact) {
        return exact.setScale(PERCENT, RoundingMode.HALF_EVEN);
    }

    /** Every figure of BR1.4 to BR1.6 for one order. */
    public record Estimate(
            BigDecimal orderValue,
            BigDecimal consumedPct,
            BigDecimal slippagePct,
            BigDecimal valueWithSlippage,
            BigDecimal fee,
            BigDecimal buyCost,
            BigDecimal exitPrice,
            BigDecimal exitGross,
            BigDecimal exitNet,
            BigDecimal equityDrop) {}
}
