package com.memetrade.bots.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.memetrade.bots.application.RealDecisionStep;
import com.memetrade.bots.domain.BotMarket;
import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.infra.BotAudit;
import com.memetrade.bots.infra.CycleAuthContext;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.bots.infra.JdbcCycleRepository;
import com.memetrade.bots.infra.LockOrder;
import com.memetrade.bots.infra.OwnQuantityMetrics;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.decision.domain.DecisionAction;
import com.memetrade.decision.domain.DecisionEngine;
import com.memetrade.decision.domain.DecisionOutcome;
import com.memetrade.decision.domain.DecisionRequest;
import com.memetrade.decision.domain.DecisionScenarios;
import com.memetrade.decision.domain.ExitReason;
import com.memetrade.decision.domain.NoTradeReason;
import com.memetrade.decision.domain.PositionInput;
import com.memetrade.decision.domain.TokenInputs;
import com.memetrade.decision.infra.DecisionJson;
import com.memetrade.decision.infra.JdbcDecisionRepository;
import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.api.HoldingsResult;
import com.memetrade.pnl.api.RealBotHoldings;
import com.memetrade.pnl.api.RealWalletCost;
import com.memetrade.pnl.application.HoldingsReaders;
import com.memetrade.riskassessment.api.AssessmentResult;
import com.memetrade.riskassessment.api.RiskBand;
import com.memetrade.riskcontrol.api.OrderGate;
import com.memetrade.riskcontrol.api.RealAccountLimits;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 261121-paper-real-unico U4 (test support of the Etapas 4 and 5): the REAL cycle of the WORKER assembled from the beans
 * of the api test context of {@code AbstractRealExecutionIT} (the cycle classes exist only in the worker): the real
 * {@link CycleRunner}, {@link RealCycle}, {@link OwnHoldingsStep}, {@code RealBotHoldingsReader}, barrier, decision
 * step, ledger and execution queue, over the real database. Only two edges are scripted: the decision engine (a SELL or
 * BUY of a given mint, the way the engine asks for the whole position it was given) and the market reader of the cycle
 * (the windows are irrelevant to the cut). The read of the own holdings is counted and can be made to fail.
 */
final class OwnQuantityCycle {

    final MeterRegistry meters = new SimpleMeterRegistry();
    final OwnHoldingsStreaks streaks;
    final OwnQuantityMetrics metrics;
    final CountingHoldings holdings;
    final OwnHoldingsStep step;
    /** The repository the step before the transaction reads (a delegating double, so a test can make P1 to P3 fail). */
    final JdbcBotRepository stepBots;

    final RealCycle realCycle;
    final CycleRunner runner;
    final List<DecisionRequest> requests = new CopyOnWriteArrayList<>();
    final TestFlag flag;
    private volatile Function<DecisionRequest, DecisionOutcome> script = OwnQuantityCycle::nothing;

    OwnQuantityCycle(ApplicationContext context, boolean flagOn) {
        this(context, flagOn, HoldingsReaders.of(context));
    }

    OwnQuantityCycle(ApplicationContext context, boolean flagOn, RealBotHoldings reader) {
        Clock clock = context.getBean(Clock.class);
        PlatformTransactionManager manager = context.getBean(PlatformTransactionManager.class);
        JdbcBotRepository bots = context.getBean(JdbcBotRepository.class);
        this.flag = new TestFlag(flagOn);
        this.streaks = new OwnHoldingsStreaks(clock);
        this.metrics = new OwnQuantityMetrics(meters, streaks);
        this.holdings = new CountingHoldings(reader);
        // A delegating double, not a spy: the bean is a proxy of Spring (the translation of @Repository).
        this.stepBots = mock(JdbcBotRepository.class, org.mockito.AdditionalAnswers.delegatesTo(bots));
        this.step = new OwnHoldingsStep(stepBots, holdings, flag, metrics, streaks, clock);
        this.realCycle = new RealCycle(
                context.getBean(RealStateQuery.class),
                context.getBean(RealLedger.class),
                context.getBean(MarketDataQuery.class),
                context.getBean(RealDecisionStep.class),
                context.getBean(RealAccountLimits.class),
                context.getBean(RealWalletCost.class),
                manager);
        DecisionEngine engine = mock(DecisionEngine.class);
        when(engine.decide(any())).thenAnswer(call -> {
            DecisionRequest request = call.getArgument(0);
            requests.add(request);
            return script.apply(request);
        });
        CycleMarketReader market = mock(CycleMarketReader.class);
        when(market.read(any(), any())).thenAnswer(call -> inputs(call.getArgument(0)));
        when(market.listed(any(), any())).thenReturn(List.of());
        JdbcCycleRepository cycles = context.getBean(JdbcCycleRepository.class);
        this.runner = new CycleRunner(
                new CycleAuthContext(),
                bots,
                cycles,
                context.getBean(JdbcDecisionRepository.class),
                context.getBean(LockOrder.class),
                market,
                engine,
                new DecisionJson(),
                context.getBean(OrderGate.class),
                context.getBean(PaperExecution.class),
                context.getBean(LedgerReadModel.class),
                context.getBean(BotAudit.class),
                new CycleFailureRecorder(cycles, clock, manager, meters),
                clock,
                manager,
                Optional.empty());
        runner.useRealCycle(realCycle);
        runner.useOwnQuantity(step, flag, metrics);
    }

    /** One cycle of a REAL bot, as the slot dispatcher hands it (with the market of the bot). */
    CycleResult run(BotRef bot, Instant slot) {
        return runner.runDispatched(new DispatchedBot(bot, BotMarket.REAL), slot);
    }

    /** From now on the engine sells the whole position it was given in {@code mint} (or nothing without it). */
    OwnQuantityCycle sellsAll(String mint) {
        script = request -> request.positions().stream()
                .filter(position -> position.token().mint().equals(mint))
                .findFirst()
                .map(position -> sell(position.token(), position.quantity()))
                .orElseGet(() -> nothing(request));
        return this;
    }

    /** From now on the engine buys {@code quantity} tokens of {@code mint}; {@code asked} records that it was asked. */
    OwnQuantityCycle buys(String mint, String quantity, java.util.concurrent.atomic.AtomicBoolean asked) {
        script = request -> {
            asked.set(true);
            return new DecisionOutcome(
                    DecisionAction.BUY,
                    Optional.of(new TokenId("solana", mint)),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new BigDecimal(quantity)),
                    List.of(),
                    "compra",
                    DecisionScenarios.NOW.plus(Duration.ofMinutes(5)));
        };
        return this;
    }

    /** From now on the engine sells {@code quantity} tokens of {@code mint}, whatever position it was given. */
    OwnQuantityCycle sells(String mint, String quantity) {
        script = request -> sell(new TokenId("solana", mint), new BigDecimal(quantity));
        return this;
    }

    /** From now on the engine decides nothing. */
    OwnQuantityCycle decidesNothing() {
        script = OwnQuantityCycle::nothing;
        return this;
    }

    /** The last request the engine saw. */
    DecisionRequest lastRequest() {
        return requests.getLast();
    }

    static DecisionOutcome sell(TokenId token, BigDecimal quantity) {
        return new DecisionOutcome(
                DecisionAction.SELL,
                Optional.of(token),
                Optional.empty(),
                Optional.of(ExitReason.MAX_HOLD),
                Optional.of(quantity),
                List.of(),
                "saida por tempo",
                DecisionScenarios.NOW.plus(Duration.ofMinutes(5)));
    }

    static DecisionOutcome nothing(DecisionRequest request) {
        return new DecisionOutcome(
                DecisionAction.NO_TRADE,
                Optional.empty(),
                Optional.of(request.tokens().isEmpty() ? NoTradeReason.NO_TOKENS : NoTradeReason.NO_SIGNAL),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                "sem sinal",
                DecisionScenarios.NOW.plus(Duration.ofMinutes(5)));
    }

    private static TokenInputs inputs(TokenId token) {
        return new TokenInputs(
                token,
                DecisionScenarios.window(token, new String[] {"1.00"}, new String[] {"1000000"}),
                DecisionScenarios.known("1.00"),
                DecisionScenarios.known("6"),
                new AssessmentResult.Available(10, RiskBand.LOW, 1));
    }

    static List<String> mints(List<PositionInput> positions) {
        return positions.stream().map(position -> position.token().mint()).toList();
    }

    /** The flag of the test: read counted, switchable. */
    static final class TestFlag implements com.memetrade.bots.api.OwnQuantitySell {
        volatile boolean on;
        final AtomicInteger reads = new AtomicInteger();

        TestFlag(boolean on) {
            this.on = on;
        }

        @Override
        public boolean enabled() {
            reads.incrementAndGet();
            return on;
        }
    }

    /** The real read of the own holdings, counted, or a forced answer or failure. */
    static final class CountingHoldings implements RealBotHoldings {
        final RealBotHoldings real;
        final AtomicInteger calls = new AtomicInteger();
        volatile Function<UUID, HoldingsResult> forced;
        volatile Runnable before = () -> {};
        volatile Runnable after = () -> {};

        CountingHoldings(RealBotHoldings real) {
            this.real = real;
        }

        @Override
        public HoldingsResult holdings(UUID tenantId, UUID userId, UUID botId, Duration budget) {
            calls.incrementAndGet();
            before.run();
            Function<UUID, HoldingsResult> answer = forced;
            HoldingsResult result =
                    answer != null ? answer.apply(botId) : real.holdings(tenantId, userId, botId, budget);
            after.run();
            return result;
        }
    }
}
