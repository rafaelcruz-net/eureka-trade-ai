package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.memetrade.bots.application.RealDecisionStep;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.decision.domain.DecisionAction;
import com.memetrade.decision.domain.DecisionEngine;
import com.memetrade.decision.domain.DecisionOutcome;
import com.memetrade.decision.domain.DecisionScenarios;
import com.memetrade.decision.domain.ExitReason;
import com.memetrade.decision.domain.PositionInput;
import com.memetrade.ledger.api.RealCostUnknownReason;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.api.WalletCost;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.riskcontrol.api.RealAccountLimits;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 261121-paper-real-unico U2, Etapa 12 (written before the code; BR2.7, F-3, reliability-design.md "Ciclo REAL"): with
 * the read of cost failing, the REAL cycle still gets every quantity from the ledger and the cost as unknown; it decides
 * no buy of the affected token; an exit of the price or the time is decided and its sale is accepted by the barrier; an
 * exit of the cost (take profit) is skipped; the cycle ends without an error.
 */
@Tag("u2-real-bot-pnl")
class RealCycleAttributionIT extends AbstractRealExecutionIT {

    @Autowired
    private RealStateQuery states;

    @Autowired
    private MarketDataQuery marketData;

    @Autowired
    private RealDecisionStep step;

    @Autowired
    private RealAccountLimits accountLimits;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void withTheReadOfCostFailingTheCycleSellsByTimeSkipsTheCostExitsAndBuysNothingOfTheToken() {
        Setup setup = realBot("AUTO_FULL");
        String held = token("0.0001", 6, 5);
        String other = token("0.0001", 6, 5);
        position(wallet(setup), held, 1_000_000, 10_000_000L);
        position(wallet(setup), other, 1_000_000, 10_000_000L);
        RealCycle cycle = new RealCycle(
                states,
                ledger,
                marketData,
                step,
                accountLimits,
                (tenant, user, botWallet, path, rows) -> new WalletCost.Unknown(RealCostUnknownReason.TIMEOUT),
                transactionManager);
        Bot bot = mock(Bot.class);
        when(bot.scope()).thenReturn(new TenantScope(setup.tenantId(), setup.userId()));
        when(bot.botId()).thenReturn(setup.botId());
        Instant now = DecisionScenarios.NOW;
        Map<TokenId, Instant> holdings = Map.of(
                new TokenId("solana", held), now.minusSeconds(240 * 60),
                new TokenId("solana", other), now.minusSeconds(10 * 60));

        RealCycle.Inputs inputs =
                transactions.execute(status -> cycle.inputs(bot, holdings, now)).orElseThrow();

        assertThat(inputs.positions()).hasSize(2).allSatisfy(p -> assertThat(p.costBasis())
                .isEmpty());
        PositionInput timeUp = position(inputs, held);
        PositionInput atTarget = position(inputs, other);
        DecisionEngine engine = new DecisionEngine();
        DecisionOutcome byTime = engine.decide(DecisionScenarios.momentum()
                .token(timeUp.token(), prices("1.00"), volumes(), "1.00")
                .position(timeUp)
                .build());
        DecisionOutcome byCost = engine.decide(DecisionScenarios.momentum()
                .token(atTarget.token(), prices("1.50"), volumes(), "1.50")
                .position(atTarget)
                .build());

        assertThat(byTime.action()).isEqualTo(DecisionAction.SELL);
        assertThat(byTime.exitReason()).contains(ExitReason.MAX_HOLD);
        assertThat(byCost.action()).as("the take profit needs the cost").isEqualTo(DecisionAction.HOLD);
        assertThat(List.of(byTime.action(), byCost.action())).doesNotContain(DecisionAction.BUY);

        String sale = transactions.execute(status -> cycle.order(bot, UUID.randomUUID(), byTime, List.of(), inputs));

        assertThat(sale).isEqualTo("REAL_RECORDED");
        assertThat(jdbc.sql("select count(*) from real_decision_request where bot_id = :b and side = 'SELL'")
                        .param("b", setup.botId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(jdbc.sql("select count(*) from real_decision_request where bot_id = :b and side = 'BUY'")
                        .param("b", setup.botId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private static PositionInput position(RealCycle.Inputs inputs, String mint) {
        return inputs.positions().stream()
                .filter(p -> p.token().mint().equals(mint))
                .findFirst()
                .orElseThrow();
    }

    private static String[] prices(String last) {
        return new String[] {"1.00", "1.00", "1.00", last};
    }

    private static String[] volumes() {
        return new String[] {"1000000", "1000000", "1000000", "1000000"};
    }
}
