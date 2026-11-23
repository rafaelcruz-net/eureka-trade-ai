package com.memetrade.bots.worker;

import static com.memetrade.decision.domain.DecisionScenarios.TOKEN_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.memetrade.bots.application.RealDecisionStep;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.botwallet.api.RealOperationMode;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.decision.domain.DecisionAction;
import com.memetrade.decision.domain.DecisionEngine;
import com.memetrade.decision.domain.DecisionOutcome;
import com.memetrade.decision.domain.DecisionScenarios;
import com.memetrade.decision.domain.ExitReason;
import com.memetrade.decision.domain.PositionInput;
import com.memetrade.ledger.api.RealBalance;
import com.memetrade.ledger.api.RealCost;
import com.memetrade.ledger.api.RealCostUnknownReason;
import com.memetrade.ledger.api.RealFlows;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.ledger.api.RealLedgerView;
import com.memetrade.ledger.api.RealPosition;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.pnl.api.CostPath;
import com.memetrade.pnl.api.RealWalletCost;
import com.memetrade.pnl.api.WalletCost;
import com.memetrade.riskcontrol.api.RealAccountLimits;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 261121-paper-real-unico U2, Etapa 12 (written before the code; F-3, BR2.7, reliability-design.md "Ciclo REAL"): the
 * cycle asks the cost of the positions through {@code pnl.api.RealWalletCost} (path CYCLE, with the rows the view
 * counted), and the quantity always arrives. An unknown cost leaves the cost of the position unknown (never zero): the
 * exits that depend only on the price or the time still decide, the exits that depend on the cost (take profit, stop
 * loss) are skipped; a buy is never decided for a token with a position.
 */
class RealCycleCostUnknownTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WALLET = UUID.randomUUID();
    private static final String WRAPPED_SOL = "So11111111111111111111111111111111111111112";
    private static final SourceRef SOURCE = new SourceRef("coingecko", "CoinGecko", false);

    private final RealStateQuery states = mock(RealStateQuery.class);
    private final RealLedger ledger = mock(RealLedger.class);
    private final MarketDataQuery market = mock(MarketDataQuery.class);
    // The read from the epoch (Etapa 14a) defaults to the read with the count of rows of the view, stubbed below.
    private final RealWalletCost walletCost = mock(RealWalletCost.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    private final RealCycle cycle = new RealCycle(
            states,
            ledger,
            market,
            mock(RealDecisionStep.class),
            mock(RealAccountLimits.class),
            walletCost,
            mock(PlatformTransactionManager.class));
    private final Bot bot = mock(Bot.class);

    private void walletWith(List<RealPosition> positions) {
        when(bot.scope()).thenReturn(new TenantScope(TENANT, OWNER));
        when(states.readRealState(TENANT, OWNER))
                .thenReturn(Optional.of(new RealStateView(
                        WALLET, true, true, RealOperationMode.AUTO_FULL, 10_000_000_000L, 0, Set.of(), Map.of())));
        when(market.referencePrice(any())).thenAnswer(call -> {
            com.memetrade.marketdata.api.TokenId token = call.getArgument(0);
            return WRAPPED_SOL.equals(token.mint()) ? known("150") : known("0.001");
        });
        when(market.decimals(any())).thenReturn(known("6"));
        when(ledger.view(TENANT, WALLET, Optional.empty()))
                .thenReturn(new RealLedgerView(
                        new RealBalance(5_000_000_000L, 0, 5_000_000_000L, Set.of(), !positions.isEmpty()),
                        positions,
                        RealFlows.NONE,
                        11));
    }

    private static MarketValue known(String value) {
        return new MarketValue.Known(new BigDecimal(value), SOURCE, Instant.now(), false);
    }

    private static RealPosition position(String mint) {
        return new RealPosition(mint, 2_000_000, new RealCost.Unknown(RealCostUnknownReason.NOT_READ));
    }

    @Test
    void theQuantityAlwaysArrivesAndAnUnknownCostStaysUnknown() {
        String mint = "MintCostUnknown1111111111111111111111111";
        walletWith(List.of(position(mint)));
        when(walletCost.costOf(TENANT, OWNER, WALLET, CostPath.CYCLE, 11))
                .thenReturn(new WalletCost.Unknown(RealCostUnknownReason.TIMEOUT));

        RealCycle.Inputs inputs = cycle.inputs(bot, Map.of(), Instant.now()).orElseThrow();

        assertThat(inputs.positions()).singleElement().satisfies(p -> {
            assertThat(p.quantity()).isEqualByComparingTo("2");
            assertThat(p.costBasis()).isEmpty();
        });
        assertThat(inputs.raw()).extracting(RealPosition::tokenBaseUnits).containsExactly(2_000_000L);
    }

    @Test
    void aKnownCostBecomesTheCostInDollars() {
        String mint = "MintCostKnown11111111111111111111111111111";
        walletWith(List.of(position(mint)));
        when(walletCost.costOf(TENANT, OWNER, WALLET, CostPath.CYCLE, 11))
                .thenReturn(new WalletCost.Known(Map.of(mint, 1_000_000_000L)));

        RealCycle.Inputs inputs = cycle.inputs(bot, Map.of(), Instant.now()).orElseThrow();

        // 1 SOL at 150 USD.
        assertThat(inputs.positions()).singleElement().satisfies(p -> assertThat(p.costBasis())
                .hasValueSatisfying(cost -> assertThat(cost).isEqualByComparingTo("150")));
    }

    @Test
    void withoutAPositionTheCostIsNeverAsked() {
        walletWith(List.of());

        cycle.inputs(bot, Map.of(), Instant.now()).orElseThrow();

        verify(walletCost, never()).costOf(any(), any(), any(), any(), anyLong());
    }

    @Test
    void withAnUnknownCostTheExitsOfTheCostAreSkippedAndThoseOfTheTimeDecide() {
        DecisionEngine engine = new DecisionEngine();

        DecisionOutcome atTheTarget = engine.decide(unknownCost("1.50", 10));
        DecisionOutcome pastTheMaximumTime = engine.decide(unknownCost("1.00", 240));

        assertThat(atTheTarget.action()).as("a take profit needs the cost").isEqualTo(DecisionAction.HOLD);
        assertThat(pastTheMaximumTime.action()).isEqualTo(DecisionAction.SELL);
        assertThat(pastTheMaximumTime.exitReason()).contains(ExitReason.MAX_HOLD);
    }

    @Test
    void aPositionWithUnknownCostIsNeverABuy() {
        DecisionOutcome outcome = new DecisionEngine().decide(unknownCost("1.20", 1));

        assertThat(outcome.action()).isNotEqualTo(DecisionAction.BUY);
        assertThat(PositionInput.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .contains("costBasis");
    }

    private static com.memetrade.decision.domain.DecisionRequest unknownCost(String reference, long minutesAgo) {
        return DecisionScenarios.momentum()
                .token(
                        TOKEN_A,
                        new String[] {"1.00", "1.00", "1.00", reference},
                        new String[] {"1000000", "1000000", "1000000", "1000000"},
                        reference)
                .positionWithUnknownCost(TOKEN_A, "100.000000", minutesAgo)
                .build();
    }
}
