package com.memetrade.riskcontrol.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.riskcontrol.api.OrderSide;
import com.memetrade.riskcontrol.domain.SlippageEstimate.Estimate;
import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 6 of U6, WRITTEN BEFORE THE CODE from BR1.4 to BR1.6 and the base of the catalogue (functional-spec.md):
 * value, consumption and slippage with 4 decimals, buy cost, net exit value and the equity drop of 3.600000; zero drop
 * on a sell; no 10% consumption cap. Values compared by exact text.
 */
@Tag("u6")
class SlippageEstimateTest {

    private static final BigDecimal PRICE = new BigDecimal("1.000000000000");
    private static final BigDecimal LIQUIDITY = new BigDecimal("10000.000000");

    private final SlippageEstimate policy = SlippageEstimate.V1;

    @Test
    void theBaseBuyHasTheFiguresOfTheCatalogue() {
        Estimate estimate = policy.estimate(OrderSide.BUY, new BigDecimal("100.000000"), PRICE, LIQUIDITY);

        assertThat(estimate.orderValue().toPlainString()).isEqualTo("100.000000");
        assertThat(estimate.consumedPct().toPlainString()).isEqualTo("1.0000");
        assertThat(estimate.slippagePct().toPlainString()).isEqualTo("1.5000");
        assertThat(estimate.valueWithSlippage().toPlainString()).isEqualTo("101.500000");
        assertThat(estimate.fee().toPlainString()).isEqualTo("0.304500");
        assertThat(estimate.buyCost().toPlainString()).isEqualTo("101.804500");
    }

    @Test
    void theExitOfTheBaseBuyAndTheEquityDrop() {
        Estimate estimate = policy.estimate(OrderSide.BUY, new BigDecimal("100.000000"), PRICE, LIQUIDITY);

        assertThat(estimate.exitPrice().toPlainString()).isEqualTo("0.985000000000");
        assertThat(estimate.exitGross().toPlainString()).isEqualTo("98.500000");
        assertThat(estimate.exitNet().toPlainString()).isEqualTo("98.204500");
        assertThat(estimate.equityDrop().toPlainString()).isEqualTo("3.600000");
    }

    @Test
    void aSellDropsNothingFromTheEquity() {
        Estimate estimate = policy.estimate(OrderSide.SELL, new BigDecimal("150.000000"), PRICE, LIQUIDITY);

        assertThat(estimate.slippagePct().toPlainString()).isEqualTo("2.0000");
        assertThat(estimate.equityDrop().toPlainString()).isEqualTo("0.000000");
        assertThat(estimate.orderValue().toPlainString()).isEqualTo("150.000000");
    }

    @Test
    void theConsumptionIsRoundedHalfEvenToFourDecimals() {
        Estimate past = policy.estimate(OrderSide.BUY, new BigDecimal("150.010000"), PRICE, LIQUIDITY);
        assertThat(past.consumedPct().toPlainString()).isEqualTo("1.5001");
        assertThat(past.slippagePct().toPlainString()).isEqualTo("2.0001");

        // 100.005 / 10000 x 100 = 1.00005 -> 1.0000 (half to even); 100.015 -> 1.00015 -> 1.0002.
        assertThat(policy.estimate(OrderSide.BUY, new BigDecimal("100.005000"), PRICE, LIQUIDITY)
                        .consumedPct()
                        .toPlainString())
                .isEqualTo("1.0000");
        assertThat(policy.estimate(OrderSide.BUY, new BigDecimal("100.015000"), PRICE, LIQUIDITY)
                        .consumedPct()
                        .toPlainString())
                .isEqualTo("1.0002");
    }

    @Test
    void aConsumptionAboveTenPercentHasNoCap() {
        Estimate big = policy.estimate(OrderSide.BUY, new BigDecimal("2000.000000"), PRICE, LIQUIDITY);

        assertThat(big.consumedPct().toPlainString()).isEqualTo("20.0000");
        assertThat(big.slippagePct().toPlainString()).isEqualTo("20.5000");
    }

    @Test
    void theOrderValueUsesTheReferencePriceWithTwelveDecimals() {
        Estimate tiny = policy.estimate(
                OrderSide.BUY, new BigDecimal("1000000.000000"), new BigDecimal("0.000012345678"), LIQUIDITY);

        assertThat(tiny.orderValue().toPlainString()).isEqualTo("12.345678");
        assertThat(tiny.consumedPct().toPlainString()).isEqualTo("0.1235");
        assertThat(tiny.slippagePct().toPlainString()).isEqualTo("0.6235");
    }

    @Test
    void theCopyOfThePolicyHasTheValuesOfV1() {
        assertThat(policy.spreadPct().toPlainString()).isEqualTo("0.5000");
        assertThat(policy.impactPerConsumedPct().toPlainString()).isEqualTo("1.0000");
        assertThat(policy.feePct().toPlainString()).isEqualTo("0.3000");
    }

    @Test
    void anExitPriceBelowZeroIsFlooredAtZero() {
        Estimate absurd = policy.estimate(OrderSide.BUY, new BigDecimal("2000000.000000"), PRICE, LIQUIDITY);

        assertThat(absurd.exitPrice().signum()).isZero();
        assertThat(absurd.exitNet().toPlainString()).isEqualTo("0.000000");
        assertThat(absurd.equityDrop()).isEqualByComparingTo(absurd.buyCost());
    }
}
