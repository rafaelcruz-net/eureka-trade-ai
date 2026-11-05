package com.memetrade.riskcontrol.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.riskcontrol.api.OrderSide;
import com.memetrade.riskcontrol.api.RejectionReason;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 7 of U6 (BR3.1; NFR4.1, NFR8.2): 1000 random combinations with a FIXED seed. Whatever the inputs, an approved
 * order never passes any limit, recomputed here independently from the rules (never through the production
 * classes), and every rejection has at least one reason, each with its detail, in the fixed order. The same inputs
 * always give the same result.
 */
@Tag("u6")
class RiskGatePropertyTest {

    private static final long SEED = 20261028L;
    private static final int COMBINATIONS = 1000;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final LimitChecks checks = new LimitChecks(SlippageEstimate.V1, Duration.ofSeconds(60));

    @Test
    void anApprovedOrderNeverPassesAnyLimitAndEveryRejectionExplainsItself() {
        Random random = new Random(SEED);
        int approved = 0;
        int rejected = 0;
        for (int i = 0; i < COMBINATIONS; i++) {
            GateScenario scenario = randomScenario(random);
            GateInputs inputs = scenario.inputs();

            CheckOutcome outcome = checks.evaluate(inputs);

            assertThat(checks.evaluate(inputs))
                    .as("deterministic, combination " + i)
                    .isEqualTo(outcome);
            if (outcome.approved()) {
                approved++;
                assertWithinEveryLimit(scenario, i);
            } else {
                rejected++;
                assertThat(outcome.details()).as("combination " + i).isNotEmpty();
                List<RejectionReason> reasons = outcome.reasons();
                assertThat(reasons).isEqualTo(new ArrayList<>(EnumSet.copyOf(reasons)));
                outcome.details().forEach(d -> assertThat(d.measured()).isNotBlank());
            }
        }
        // The generator reaches both outcomes often enough to make the property meaningful.
        assertThat(approved).isGreaterThan(50);
        assertThat(rejected).isGreaterThan(50);
    }

    /** The rules of BR2.1 to BR2.11 recomputed in plain BigDecimal arithmetic, without the production code. */
    private static void assertWithinEveryLimit(GateScenario s, int i) {
        String at = "combination " + i;
        MarketValue.Known price = (MarketValue.Known) s.price;
        MarketValue.Known liquidity = (MarketValue.Known) s.liquidity;
        assertThat(Math.max(
                        0,
                        Duration.between(price.observedAt(), GateScenario.NOW).getSeconds()))
                .as(at)
                .isLessThanOrEqualTo(60);
        assertThat(Math.max(
                        0,
                        Duration.between(liquidity.observedAt(), GateScenario.NOW)
                                .getSeconds()))
                .as(at)
                .isLessThanOrEqualTo(60);
        assertThat(liquidity.value()).as(at).isGreaterThanOrEqualTo(new BigDecimal(s.minLiquidity));

        BigDecimal quantity = new BigDecimal(s.quantity);
        BigDecimal value = quantity.multiply(price.value()).setScale(6, RoundingMode.HALF_EVEN);
        BigDecimal consumed = value.multiply(HUNDRED).divide(liquidity.value(), 4, RoundingMode.HALF_EVEN);
        BigDecimal slippage = new BigDecimal("0.5000").add(consumed);
        assertThat(slippage).as(at).isLessThanOrEqualTo(new BigDecimal(s.maxSlippagePct));

        if (s.side == OrderSide.BUY) {
            assertThat(value).as(at).isLessThanOrEqualTo(new BigDecimal(s.maxOrderSize));
            assertThat(new BigDecimal(s.tokenExposure).add(value))
                    .as(at)
                    .isLessThanOrEqualTo(new BigDecimal(s.maxTokenExposure));
            assertThat(new BigDecimal(s.totalExposure).add(value))
                    .as(at)
                    .isLessThanOrEqualTo(new BigDecimal(s.maxTotalExposure));
            BigDecimal withSlippage =
                    value.multiply(BigDecimal.ONE.add(slippage.divide(HUNDRED))).setScale(6, RoundingMode.HALF_EVEN);
            BigDecimal cost = withSlippage.add(
                    withSlippage.multiply(new BigDecimal("0.003")).setScale(6, RoundingMode.HALF_EVEN));
            assertThat(cost).as(at).isLessThanOrEqualTo(new BigDecimal(s.balance));
        }
        s.lastTradeAgo.ifPresent(
                ago -> assertThat(ago.getSeconds()).as(at).isGreaterThanOrEqualTo(s.minIntervalSeconds));
        assertThat(new BigDecimal(s.dayLoss)).as(at).isLessThan(new BigDecimal(s.maxDailyLoss));
        assertThat(s.equity).as(at).isPresent();
        BigDecimal floor = new BigDecimal(s.peakEquity)
                .multiply(BigDecimal.ONE.subtract(new BigDecimal(s.maxDrawdownPct).divide(HUNDRED)))
                .setScale(6, RoundingMode.HALF_EVEN);
        assertThat(new BigDecimal(s.equity.get())).as(at).isGreaterThan(floor);
    }

    private static GateScenario randomScenario(Random random) {
        GateScenario s = GateScenario.base();
        s.side = random.nextInt(4) == 0 ? OrderSide.SELL : OrderSide.BUY;
        s.quantity = decimal(random, 1, 250_000_000L, 6);
        s.price = random.nextInt(12) == 0
                ? GateScenario.unknown()
                : GateScenario.known(decimal(random, 500_000_000_000L, 1_500_000_000_000L, 12), age(random));
        s.liquidity = random.nextInt(12) == 0
                ? GateScenario.unknown()
                : GateScenario.known(decimal(random, 5_000_000_000L, 40_000_000_000L, 6), age(random));
        s.tokenExposure = decimal(random, 0, 150_000_000L, 6);
        s.totalExposure = new BigDecimal(s.tokenExposure)
                .add(new BigDecimal(decimal(random, 0, 300_000_000L, 6)))
                .toPlainString();
        s.dayLoss = decimal(random, 0, 60_000_000L, 6);
        s.peakEquity = decimal(random, 1_000_000_000L, 1_200_000_000L, 6);
        s.equity = random.nextInt(15) == 0
                ? Optional.empty()
                : Optional.of(decimal(random, 850_000_000L, Long.parseLong(s.peakEquity.replace(".", "")), 6));
        s.balance = decimal(random, 0, 400_000_000L, 6);
        s.lastTradeAgo =
                random.nextInt(3) == 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(random.nextInt(900)));
        s.maxOrderSize = decimal(random, 50_000_000L, 250_000_000L, 6);
        s.maxTotalExposure = decimal(random, 200_000_000L, 700_000_000L, 6);
        s.maxTokenExposure = decimal(random, 100_000_000L, Long.parseLong(s.maxTotalExposure.replace(".", "")), 6);
        s.maxSlippagePct = decimal(random, 10_000L, 40_000L, 4);
        s.minLiquidity = decimal(random, 0, 20_000_000_000L, 6);
        s.minIntervalSeconds = random.nextInt(600);
        s.maxDailyLoss = decimal(random, 10_000_000L, 70_000_000L, 6);
        s.maxDrawdownPct = decimal(random, 10_000L, 300_000L, 4);
        return s;
    }

    /** Ages from 10 s in the future to 90 s in the past. */
    private static Duration age(Random random) {
        return Duration.ofSeconds(random.nextInt(100) - 10);
    }

    private static String decimal(Random random, long minUnits, long maxUnits, int scale) {
        long units = minUnits + Math.floorMod(random.nextLong(), maxUnits - minUnits + 1);
        return BigDecimal.valueOf(units, scale).toPlainString();
    }
}
