package com.memetrade.riskcontrol;

import static com.memetrade.riskcontrol.RiskGateFixtures.baseLimits;
import static com.memetrade.riskcontrol.RiskGateFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.PaperEnums;
import com.memetrade.ledger.api.PaperExecution.ExecutionResult;
import com.memetrade.ledger.api.PaperExecution.PaperOrderRequest;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.api.PortfolioRiskView;
import com.memetrade.riskcontrol.api.BotLimitsVersionRef;
import com.memetrade.riskcontrol.api.CandidateOrder;
import com.memetrade.riskcontrol.api.GateResult;
import com.memetrade.riskcontrol.api.OrderGate;
import com.memetrade.riskcontrol.api.OrderSide;
import com.memetrade.riskcontrol.domain.SlippageEstimate;
import com.memetrade.testsupport.AbstractU6IT;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 10 of U6 (BR1.5, BR1.6; Q5 of the Functional Design): the copy of the simulation policy in the barrier stays
 * equal to the policy IN FORCE of U7, read from the table the U7 migration seeds (never a fixed v1 constant); for E1,
 * E4 and E16 the estimated slippage equals the {@code slippagePct} of the real fill of U7, and for a bot without a
 * position in the token the estimated equity drop equals the real change of the equity of U7 (3.600000 in the base).
 */
class SlippageConformanceIT extends AbstractU6IT {

    private static final BigDecimal PRICE = new BigDecimal("1.000000000000");
    private static final BigDecimal LIQUIDITY = new BigDecimal("10000.000000");

    @Autowired
    private OrderGate gate;

    @Autowired
    private PortfolioRiskView riskView;

    private Principal person;
    private TokenId token;

    @BeforeEach
    void seed() {
        person = newPerson();
        token = newToken();
        marketNow(token, "1.000000000000", "10000.000000");
    }

    @Test
    void theCopyEqualsThePolicyInForceOfU7() {
        Map<String, Object> inForce = jdbc.sql("""
                        select spread_pct, impact_per_consumed_pct, fee_pct from simulation_policy
                        where activated_at <= :now order by activated_at desc limit 1
                        """)
                .param("now", java.sql.Timestamp.from(clock.instant()))
                .query()
                .singleRow();

        SlippageEstimate copy = SlippageEstimate.V1;
        assertThat(copy.spreadPct()).isEqualByComparingTo((BigDecimal) inForce.get("spread_pct"));
        assertThat(copy.impactPerConsumedPct())
                .isEqualByComparingTo((BigDecimal) inForce.get("impact_per_consumed_pct"));
        assertThat(copy.feePct()).isEqualByComparingTo((BigDecimal) inForce.get("fee_pct"));
    }

    @Test
    void e1TheEstimatedSlippageEqualsTheSlippageOfTheRealFill() {
        UUID bot = fundedBot(person, "1000.000000");
        assertSameSlippage(bot, OrderSide.BUY, "100.000000", baseLimits(), "1.5000");
    }

    @Test
    void e4TheEstimatedSlippageAtTheLimitEqualsTheRealFill() {
        UUID bot = fundedBot(person, "1000.000000");
        assertSameSlippage(bot, OrderSide.BUY, "150.000000", RiskGateFixtures.limits("200.000000"), "2.0000");
    }

    @Test
    void e16TheEstimatedSlippageOfASellEqualsTheRealFill() {
        UUID bot = fundedBot(person, "1000.000000");
        BotLimitsVersionRef wider = RiskGateFixtures.limits("200.000000");
        assertSameSlippage(bot, OrderSide.BUY, "150.000000", wider, "2.0000");
        clock.advance(java.time.Duration.ofSeconds(300));
        marketNow(token, "1.000000000000", "10000.000000");

        assertSameSlippage(bot, OrderSide.SELL, "150.000000", wider, "2.0000");
    }

    @Test
    void theEstimatedEquityDropOfABuyEqualsTheRealChangeOfTheEquityOfU7() {
        UUID bot = fundedBot(person, "1000.000000");
        BigDecimal before = equity(bot);

        assertSameSlippage(bot, OrderSide.BUY, "100.000000", baseLimits(), "1.5000");
        BigDecimal after = equity(bot);

        BigDecimal estimated = SlippageEstimate.V1
                .estimate(OrderSide.BUY, new BigDecimal("100.000000"), PRICE, LIQUIDITY)
                .equityDrop();
        assertThat(estimated.toPlainString()).isEqualTo("3.600000");
        assertThat(before.subtract(after)).isEqualByComparingTo(estimated);
    }

    private void assertSameSlippage(
            UUID bot, OrderSide side, String quantity, BotLimitsVersionRef limits, String expected) {
        CandidateOrder candidate = order(bot, token, side, quantity);
        GateResult result = inCycle(person, () -> gate.evaluate(candidate, limits));
        assertThat(result).isInstanceOf(GateResult.Approved.class);

        ExecutionResult executed = inCycle(
                person,
                () -> paperExecution.execute(
                        new PaperOrderRequest(
                                bot,
                                candidate.decisionId(),
                                PaperEnums.OrderSide.valueOf(side.name()),
                                token.chain(),
                                token.mint(),
                                candidate.requestedQuantity(),
                                limits.version()),
                        UUID.randomUUID().toString()));

        assertThat(executed).isInstanceOf(ExecutionResult.Filled.class);
        BigDecimal real = ((ExecutionResult.Filled) executed).fill().slippagePct();
        BigDecimal estimated = SlippageEstimate.V1
                .estimate(side, candidate.requestedQuantity(), PRICE, LIQUIDITY)
                .slippagePct();
        assertThat(estimated.toPlainString()).isEqualTo(expected);
        assertThat(real).isEqualByComparingTo(estimated);
    }

    private BigDecimal equity(UUID bot) {
        return inCycle(person, () -> riskView.snapshot(
                                bot, token.chain(), token.mint(), LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))
                        .equity())
                .orElseThrow();
    }
}
