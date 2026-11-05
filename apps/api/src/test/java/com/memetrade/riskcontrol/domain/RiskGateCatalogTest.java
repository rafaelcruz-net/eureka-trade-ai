package com.memetrade.riskcontrol.domain;

import static com.memetrade.riskcontrol.api.RejectionReason.DAILY_LOSS;
import static com.memetrade.riskcontrol.api.RejectionReason.DRAWDOWN;
import static com.memetrade.riskcontrol.api.RejectionReason.DUPLICATE;
import static com.memetrade.riskcontrol.api.RejectionReason.INSUFFICIENT_PAPER_BALANCE;
import static com.memetrade.riskcontrol.api.RejectionReason.INTERVAL;
import static com.memetrade.riskcontrol.api.RejectionReason.MIN_LIQUIDITY;
import static com.memetrade.riskcontrol.api.RejectionReason.ORDER_SIZE;
import static com.memetrade.riskcontrol.api.RejectionReason.SLIPPAGE;
import static com.memetrade.riskcontrol.api.RejectionReason.STALE_DATA;
import static com.memetrade.riskcontrol.api.RejectionReason.TOKEN_EXPOSURE;
import static com.memetrade.riskcontrol.api.RejectionReason.TOTAL_EXPOSURE;
import static com.memetrade.riskcontrol.domain.GateScenario.base;
import static com.memetrade.riskcontrol.domain.GateScenario.known;
import static com.memetrade.riskcontrol.domain.GateScenario.unknown;
import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.riskcontrol.api.PauseTrigger;
import com.memetrade.riskcontrol.api.RejectionReason;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 5 of U6, WRITTEN BEFORE THE CODE from the catalogue E1 to E19 of functional-spec.md (AC4.6.1, AC4.6.2,
 * AC4.7.1, AC4.7.2; BR2.1 to BR2.12, BR3.1 to BR3.3; NFR4.1, NFR4.5). Every allowed case is the boundary itself and
 * every refused case one increment past it; every value is compared by exact text, never computed in the test. E20 and
 * E21 need two transactions and live in EvaluationDuplicateIT.
 */
@Tag("u6")
class RiskGateCatalogTest {

    private final LimitChecks checks = new LimitChecks(SlippageEstimate.V1, Duration.ofSeconds(60));

    // --- the base -----------------------------------------------------------------------------------------------

    @Test
    void theBaseOrderIsApprovedWithItsDerivedFigures() {
        CheckOutcome outcome = checks.evaluate(base().inputs());

        assertThat(outcome.approved()).isTrue();
        assertThat(outcome.pauseTriggers()).isEmpty();
        assertThat(outcome.orderValue().text()).isEqualTo("100.000000");
        assertThat(outcome.estimatedSlippagePct().text()).isEqualTo("1.5000");
    }

    // --- E1 to E13 ----------------------------------------------------------------------------------------------

    @Test
    void e1OrderSize() {
        assertThat(checks.evaluate(base().buy("100.000000").inputs()).approved())
                .isTrue();

        CheckOutcome refused = checks.evaluate(base().buy("100.000001").inputs());
        assertRefused(refused, ORDER_SIZE);
        assertDetail(refused, ORDER_SIZE, "100.000001", "100.000000", DetailUnit.USDC);
    }

    @Test
    void e2TokenExposure() {
        assertThat(checks.evaluate(base().exposures("100.000000", "100.000000").inputs())
                        .approved())
                .isTrue();

        CheckOutcome refused =
                checks.evaluate(base().exposures("100.000001", "100.000001").inputs());
        assertRefused(refused, TOKEN_EXPOSURE);
        assertDetail(refused, TOKEN_EXPOSURE, "200.000001", "200.000000", DetailUnit.USDC);
    }

    @Test
    void e3TotalExposure() {
        assertThat(checks.evaluate(base().exposures("0.000000", "400.000000").inputs())
                        .approved())
                .isTrue();

        CheckOutcome refused =
                checks.evaluate(base().exposures("0.000000", "400.000001").inputs());
        assertRefused(refused, TOTAL_EXPOSURE);
        assertDetail(refused, TOTAL_EXPOSURE, "500.000001", "500.000000", DetailUnit.USDC);
    }

    @Test
    void e4Slippage() {
        GateScenario allowed = base().buy("150.000000");
        allowed.maxOrderSize = "200.000000";
        CheckOutcome approved = checks.evaluate(allowed.inputs());
        assertThat(approved.approved()).isTrue();
        assertThat(approved.estimatedSlippagePct().text()).isEqualTo("2.0000");

        GateScenario past = base().buy("150.010000");
        past.maxOrderSize = "200.000000";
        CheckOutcome refused = checks.evaluate(past.inputs());
        assertRefused(refused, SLIPPAGE);
        assertDetail(refused, SLIPPAGE, "2.0001", "2.0000", DetailUnit.PERCENT);
        assertThat(refused.estimatedSlippagePct().text()).isEqualTo("2.0001");
    }

    @Test
    void e5MinimumLiquidity() {
        assertThat(checks.evaluate(base().inputs()).approved()).isTrue();

        GateScenario below = base();
        below.liquidity = known("9999.999999", Duration.ZERO);
        CheckOutcome refused = checks.evaluate(below.inputs());
        assertRefused(refused, MIN_LIQUIDITY);
        assertDetail(refused, MIN_LIQUIDITY, "9999.999999", "10000.000000", DetailUnit.USDC);

        GateScenario missing = base();
        missing.liquidity = unknown();
        CheckOutcome unknownLiquidity = checks.evaluate(missing.inputs());
        assertRefused(unknownLiquidity, MIN_LIQUIDITY);
        assertDetail(unknownLiquidity, MIN_LIQUIDITY, "UNKNOWN", "10000.000000", DetailUnit.USDC);
        assertThat(unknownLiquidity.estimatedSlippagePct().text()).isEqualTo("UNKNOWN");
    }

    @Test
    void e6Freshness() {
        GateScenario sixty = base();
        sixty.price = known("1.000000000000", Duration.ofSeconds(60));
        assertThat(checks.evaluate(sixty.inputs()).approved()).isTrue();

        GateScenario sixtyOne = base();
        sixtyOne.price = known("1.000000000000", Duration.ofSeconds(61));
        CheckOutcome stale = checks.evaluate(sixtyOne.inputs());
        assertRefused(stale, STALE_DATA);
        assertDetail(stale, STALE_DATA, "61", "60", DetailUnit.SECONDS);
        assertThat(stale.orderValue().text()).isEqualTo("UNKNOWN");

        GateScenario missing = base();
        missing.price = unknown();
        CheckOutcome unknownPrice = checks.evaluate(missing.inputs());
        assertRefused(unknownPrice, STALE_DATA);
        assertDetail(unknownPrice, STALE_DATA, "UNKNOWN", "60", DetailUnit.SECONDS);
    }

    @Test
    void e7Interval() {
        GateScenario atInterval = base();
        atInterval.lastTradeAgo = Optional.of(Duration.ofSeconds(300));
        assertThat(checks.evaluate(atInterval.inputs()).approved()).isTrue();

        GateScenario early = base();
        early.lastTradeAgo = Optional.of(Duration.ofSeconds(299));
        CheckOutcome refused = checks.evaluate(early.inputs());
        assertRefused(refused, INTERVAL);
        assertDetail(refused, INTERVAL, "299", "300", DetailUnit.SECONDS);
    }

    /** E8: the second evaluation of decision K is only DUPLICATE, with the decision and the original evaluation. */
    @Test
    void e8DuplicateOfTheSameDecision() {
        GateScenario scenario = base();
        UUID original = UUID.randomUUID();

        CheckOutcome duplicate = checks.duplicate(scenario.inputs(), original);

        assertRefused(duplicate, DUPLICATE);
        assertDetail(duplicate, DUPLICATE, scenario.decisionId.toString(), original.toString(), DetailUnit.REFERENCE);
        // The market read in the call is only recorded; no check runs on it (F2).
        GateScenario breaking = base().buy("999.000000");
        assertThat(checks.duplicate(breaking.inputs(), original).reasons()).containsExactly(DUPLICATE);
    }

    @Test
    void e9PaperBalance() {
        GateScenario exact = base();
        exact.balance = "101.804500";
        assertThat(checks.evaluate(exact.inputs()).approved()).isTrue();

        GateScenario short1 = base();
        short1.balance = "101.804499";
        CheckOutcome refused = checks.evaluate(short1.inputs());
        assertRefused(refused, INSUFFICIENT_PAPER_BALANCE);
        assertDetail(refused, INSUFFICIENT_PAPER_BALANCE, "101.804500", "101.804499", DetailUnit.USDC);
    }

    @Test
    void e10DailyLoss() {
        GateScenario below = base();
        below.dayLoss = "49.999999";
        assertThat(checks.evaluate(below.inputs()).approved()).isTrue();

        GateScenario reached = base();
        reached.dayLoss = "50.000000";
        CheckOutcome refused = checks.evaluate(reached.inputs());
        assertRefused(refused, DAILY_LOSS);
        assertDetail(refused, DAILY_LOSS, "50.000000", "50.000000", DetailUnit.USDC);
        assertThat(refused.pauseTriggers()).containsExactly(PauseTrigger.DAILY_LOSS);
    }

    @Test
    void e11DrawdownAfterTheOrder() {
        GateScenario atFloor = base();
        atFloor.equity = Optional.of("903.600000");
        assertThat(checks.evaluate(atFloor.inputs()).approved()).isTrue();

        GateScenario below = base();
        below.equity = Optional.of("903.599999");
        CheckOutcome refused = checks.evaluate(below.inputs());
        assertRefused(refused, DRAWDOWN);
        assertDetail(refused, DRAWDOWN, "10.0000", "10.0000", DetailUnit.PERCENT);
        assertThat(refused.pauseTriggers()).isEmpty();
    }

    @Test
    void e12DrawdownReached() {
        GateScenario reached = base();
        reached.equity = Optional.of("900.000000");

        CheckOutcome refused = checks.evaluate(reached.inputs());

        assertRefused(refused, DRAWDOWN);
        assertDetail(refused, DRAWDOWN, "10.0000", "10.0000", DetailUnit.PERCENT);
        assertThat(refused.pauseTriggers()).containsExactly(PauseTrigger.DRAWDOWN);
    }

    @Test
    void e13DrawdownWithoutEquity() {
        GateScenario empty = base();
        empty.equity = Optional.empty();

        CheckOutcome refused = checks.evaluate(empty.inputs());

        assertRefused(refused, DRAWDOWN);
        assertDetail(refused, DRAWDOWN, "UNKNOWN", "10.0000", DetailUnit.PERCENT);
        assertThat(refused.pauseTriggers()).isEmpty();
    }

    // --- E14 to E19: combinations and sells ---------------------------------------------------------------------

    @Test
    void e14SeveralReasonsInTheFixedOrder() {
        CheckOutcome refused = checks.evaluate(
                base().buy("100.000001").exposures("100.000000", "100.000000").inputs());

        assertThat(refused.reasons()).containsExactly(ORDER_SIZE, TOKEN_EXPOSURE);
        assertDetail(refused, TOKEN_EXPOSURE, "200.000001", "200.000000", DetailUnit.USDC);
    }

    @Test
    void e15TwoPauseTriggers() {
        GateScenario both = base();
        both.dayLoss = "50.000000";
        both.equity = Optional.of("900.000000");

        CheckOutcome refused = checks.evaluate(both.inputs());

        assertThat(refused.reasons()).containsExactly(DAILY_LOSS, DRAWDOWN);
        assertThat(refused.pauseTriggers()).containsExactly(PauseTrigger.DAILY_LOSS, PauseTrigger.DRAWDOWN);
    }

    @Test
    void e16SellSkipsSizeExposureAndBalance() {
        GateScenario sell = base().sell("150.000000").exposures("200.000000", "200.000000");
        sell.balance = "0.000000";

        CheckOutcome outcome = checks.evaluate(sell.inputs());

        assertThat(outcome.approved()).isTrue();
        assertThat(outcome.estimatedSlippagePct().text()).isEqualTo("2.0000");
        assertThat(outcome.orderValue().text()).isEqualTo("150.000000");
    }

    @Test
    void e17SellBarredByTheDailyLoss() {
        GateScenario sell = base().sell("100.000000");
        sell.dayLoss = "50.000000";

        CheckOutcome refused = checks.evaluate(sell.inputs());

        assertThat(refused.reasons()).containsExactly(DAILY_LOSS);
        assertThat(refused.pauseTriggers()).containsExactly(PauseTrigger.DAILY_LOSS);
    }

    @Test
    void e18AMissingPriceNeverBecomesZero() {
        GateScenario missing = base().exposures("199.000000", "199.000000");
        missing.price = unknown();

        CheckOutcome refused = checks.evaluate(missing.inputs());

        assertThat(refused.reasons()).containsExactly(STALE_DATA);
        assertThat(refused.orderValue().text()).isEqualTo("UNKNOWN");
        assertThat(refused.estimatedSlippagePct().text()).isEqualTo("UNKNOWN");
    }

    @Test
    void e19AStaleLiquidityIsNotUsable() {
        GateScenario old = base();
        old.liquidity = known("10000.000000", Duration.ofSeconds(61));
        old.balance = "50.000000";

        CheckOutcome refused = checks.evaluate(old.inputs());

        assertThat(refused.reasons()).containsExactly(STALE_DATA);
        assertDetail(refused, STALE_DATA, "61", "60", DetailUnit.SECONDS);
        assertThat(refused.estimatedSlippagePct().text()).isEqualTo("UNKNOWN");
        assertThat(refused.orderValue().text()).isEqualTo("100.000000");
    }

    // --- corners of functional-spec.md "Cantos tratados" --------------------------------------------------------

    @Test
    void aPriceObservedInTheFutureCountsAsZeroSecondsOld() {
        GateScenario future = base();
        future.price = known("1.000000000000", Duration.ofSeconds(-30));
        future.liquidity = known("10000.000000", Duration.ofSeconds(-30));

        assertThat(checks.evaluate(future.inputs()).approved()).isTrue();
    }

    @Test
    void aStalePriceWinsTheSingleStaleDetailOverAStaleLiquidity() {
        GateScenario both = base();
        both.price = known("1.000000000000", Duration.ofSeconds(90));
        both.liquidity = known("10000.000000", Duration.ofSeconds(120));

        CheckOutcome refused = checks.evaluate(both.inputs());

        assertThat(refused.reasons()).containsExactly(STALE_DATA);
        assertDetail(refused, STALE_DATA, "90", "60", DetailUnit.SECONDS);
    }

    @Test
    void aSellNeverRunsTheOrderSizeEvenAboveTheLimit() {
        CheckOutcome outcome = checks.evaluate(base().sell("120.000000").inputs());

        assertThat(outcome.reasons())
                .doesNotContain(ORDER_SIZE, TOKEN_EXPOSURE, TOTAL_EXPOSURE, INSUFFICIENT_PAPER_BALANCE);
    }

    @Test
    void noLastTradeMeansNoIntervalRestriction() {
        GateScenario scenario = base();
        scenario.minIntervalSeconds = 86_400;

        assertThat(checks.evaluate(scenario.inputs()).approved()).isTrue();
    }

    private static void assertRefused(CheckOutcome outcome, RejectionReason only) {
        assertThat(outcome.approved()).isFalse();
        assertThat(outcome.reasons()).containsExactly(only);
    }

    private static void assertDetail(
            CheckOutcome outcome, RejectionReason reason, String measured, String limit, DetailUnit unit) {
        ReasonDetail detail = outcome.detail(reason);
        assertThat(detail.measured()).as(reason + " measured").isEqualTo(measured);
        assertThat(detail.limit()).as(reason + " limit").isEqualTo(limit);
        assertThat(detail.unit()).as(reason + " unit").isEqualTo(unit);
    }
}
