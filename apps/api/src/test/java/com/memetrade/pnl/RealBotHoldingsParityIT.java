package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.ledger.api.RealLedgerView;
import com.memetrade.ledger.api.RealPosition;
import com.memetrade.pnl.api.BotHolding;
import com.memetrade.pnl.api.CostPath;
import com.memetrade.pnl.api.HoldingsResult;
import com.memetrade.pnl.api.RealWalletCost;
import com.memetrade.pnl.api.WalletCost;
import com.memetrade.pnl.application.HoldingsReaders;
import com.memetrade.pnl.application.RealBotHoldingsReader;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.realexecution.OpenSellsFixture;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * 261121-paper-real-unico U4, Etapa 3, T12 (written before the code; BR7.1, BR1.1, BR1.2, NFR2.9): the own holdings of
 * every bot equal the quantity and the cost of the same bot computed by the rules of the U2 for a known history, in 100%
 * of the bots and mints. Two independent checks close the parity: the own quantities of the bots add up to the quantity
 * of the wallet in the ledger, and their own costs add up to the cost of the wallet read with attribution over the WHOLE
 * history (the path {@code GATE} of the U2), not from the epoch. With open sales, {@code available = own - open}.
 *
 * <p>The P&L REAL by bot ({@code RealBotPnl}) has no read of the remaining quantity of one holder; its cost path is the
 * same {@code RealCostMethod}, so the cost read of the wallet stands for it (recorded in the code summary).
 */
@Tag("u4-own-quantity-cycle")
class RealBotHoldingsParityIT extends AbstractRealExecutionIT {

    private static final Duration BUDGET = Duration.ofMillis(250);

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RealWalletCost walletCost;

    @Test
    void withoutOpenSalesTheOwnHoldingsOfEveryBotAreItsHolderInTheLedger() {
        Setup setup = realBot("AUTO_FULL");
        OwnHoldingsScenario scenario = scenario(setup);
        RealBotHoldingsReader reader = HoldingsReaders.of(context);

        Map<String, Long> units = new HashMap<>();
        Map<String, Long> costs = new HashMap<>();
        for (UUID bot : scenario.bots()) {
            HoldingsResult result = reader.holdings(setup.tenantId(), setup.userId(), bot, BUDGET);

            Map<String, long[]> own = OwnHoldingsScenario.own(result);
            assertThat(own).as("bot %s", bot).usingRecursiveComparison().isEqualTo(scenario.expected(bot));
            for (BotHolding holding : ((HoldingsResult.Available) result).holdings()) {
                assertThat(holding.openSellBaseUnits()).isZero();
                assertThat(holding.availableTokenBaseUnits()).isEqualTo(holding.ownTokenBaseUnits());
                units.merge(holding.mint(), holding.ownTokenBaseUnits(), Long::sum);
                costs.merge(holding.mint(), holding.ownCostLamports(), Long::sum);
            }
        }

        RealLedgerView view =
                transactions.execute(status -> ledger.view(setup.tenantId(), setup.botWalletId(), Optional.empty()));
        for (RealPosition position : view.positions()) {
            if (!position.mint().equals(setup.mint())) {
                assertThat(units.get(position.mint())).as(position.mint()).isEqualTo(position.tokenBaseUnits());
            }
        }
        WalletCost whole = transactions.execute(status -> walletCost.costOf(
                setup.tenantId(), setup.userId(), setup.botWalletId(), CostPath.GATE, view.walletRowCount()));
        assertThat(whole).isInstanceOf(WalletCost.Known.class);
        ((WalletCost.Known) whole)
                .costByMint()
                .forEach((mint, cost) -> assertThat(costs.get(mint)).as(mint).isEqualTo(cost));
    }

    @Test
    void withOpenSalesTheAvailableIsTheOwnMinusTheOpen() {
        Setup setup = realBot("AUTO_FULL");
        OwnHoldingsScenario scenario = scenario(setup);
        OpenSellsFixture open = new OpenSellsFixture(jdbc);
        open.pendingSell(setup.tenantId(), setup.userId(), scenario.a, scenario.m1, 20_000_000L);
        open.order(setup.tenantId(), setup.userId(), scenario.a, "SELL", scenario.m3, 2, "AMBIGUOUS");
        open.pendingSell(setup.tenantId(), setup.userId(), scenario.a, scenario.m4, 9);

        HoldingsResult.Available result = (HoldingsResult.Available)
                HoldingsReaders.of(context).holdings(setup.tenantId(), setup.userId(), scenario.a, BUDGET);

        assertThat(result.holdings())
                .contains(
                        new BotHolding(scenario.m1, 60_000_000L, 60_000_000L, 20_000_000L, 40_000_000L),
                        new BotHolding(scenario.m3, 5, 500, 2, 3),
                        new BotHolding(scenario.m4, 3, 9, 9, 0));
    }

    private OwnHoldingsScenario scenario(Setup setup) {
        UUID b = realBotRow(setup.tenantId(), setup.userId());
        UUID c = realBotRow(setup.tenantId(), setup.userId());
        return new OwnHoldingsScenario(
                jdbc, setup.tenantId(), setup.userId(), setup.botWalletId(), setup.botId(), b, c);
    }
}
