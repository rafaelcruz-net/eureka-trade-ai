package com.memetrade.bots.worker;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.bots.api.BotState;
import com.memetrade.bots.api.OwnQuantitySell;
import com.memetrade.bots.api.PauseReason;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.BotLimits;
import com.memetrade.bots.domain.BotMarket;
import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.BotStateMachine;
import com.memetrade.bots.domain.OwnDecisionInputs;
import com.memetrade.bots.domain.OwnHoldings;
import com.memetrade.bots.domain.OwnPosition;
import com.memetrade.bots.domain.OwnQuantityReason;
import com.memetrade.bots.domain.OwnSellCap;
import com.memetrade.bots.domain.SellCapOutcome;
import com.memetrade.bots.infra.BotAudit;
import com.memetrade.bots.infra.CycleAuthContext;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.bots.infra.JdbcCycleRepository;
import com.memetrade.bots.infra.LockOrder;
import com.memetrade.bots.infra.OwnQuantityMetrics;
import com.memetrade.decision.domain.DecisionAction;
import com.memetrade.decision.domain.DecisionEngine;
import com.memetrade.decision.domain.DecisionOutcome;
import com.memetrade.decision.domain.DecisionRequest;
import com.memetrade.decision.domain.PositionInput;
import com.memetrade.decision.domain.TokenInputs;
import com.memetrade.decision.domain.TokenSelectionResolver;
import com.memetrade.decision.infra.DecisionJson;
import com.memetrade.decision.infra.JdbcDecisionRepository;
import com.memetrade.decision.infra.OwnQuantityContext;
import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.ledger.api.LedgerReadModel.PositionView;
import com.memetrade.ledger.api.PaperEnums;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.ledger.api.PaperExecution.ExecutionResult;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.api.HoldingsResult;
import com.memetrade.riskcontrol.api.CandidateOrder;
import com.memetrade.riskcontrol.api.GateResult;
import com.memetrade.riskcontrol.api.OrderGate;
import com.memetrade.riskcontrol.api.OrderSide;
import com.memetrade.riskcontrol.api.PauseTrigger;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.web.CorrelationId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One cycle of one bot in one 5-minute slot (F3; BR3.1 to BR3.7), in ONE transaction in READ COMMITTED with
 * {@code lock_timeout} 2 s and {@code statement_timeout} 5 s, as the OWNER of the bot (BR2.10, BR3.6):
 *
 * <ol>
 *   <li>claims the slot ({@code bot_cycle} unique per bot and slot; a slot already claimed is never processed again,
 *       BR3.2); a slot older than 5 minutes is SKIPPED/SLOT_EXPIRED;
 *   <li>locks ONLY the bot row and checks ACTIVE without pause reason and the kill switch (BR3.3): otherwise SKIPPED,
 *       and neither U6 nor U7 is ever called;
 *   <li>reads U7 (positions and balance) and U4 (windows, prices, assessments, scanner), decides (pure engine) and
 *       records the decision with its inputs and context (BR4.10);
 *   <li>BUY and SELL go to the barrier of U6; approved, to the execution of U7 with the key {@code order:<decisionId>};
 *       the pause triggers of a rejection pause the bot in the same transaction (BR3.4).
 * </ol>
 *
 * A technical failure anywhere rolls everything back; the FAILED slot is recorded afterwards by
 * {@link CycleFailureRecorder} and the slot is not repeated (BR3.5). The {@code cycleId} is the correlation id of
 * every log line and audit event of the cycle (NFR9.3).
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
public class CycleRunner {

    private static final Logger log = LoggerFactory.getLogger(CycleRunner.class);
    private static final Duration SLOT = Duration.ofMinutes(5);

    /**
     * 261121-paper-real-unico U1 (BR1.9): the documented sentinel recorded in {@code decision.limits_version} of a REAL
     * decision: a REAL bot has no limits version of its own; the REAL limits of the account apply.
     */
    public static final int REAL_LIMITS_VERSION = 0;

    private final CycleAuthContext auth;
    private final JdbcBotRepository bots;
    private final JdbcCycleRepository cycles;
    private final JdbcDecisionRepository decisions;
    private final LockOrder locks;
    private final CycleMarketReader market;
    private final DecisionEngine engine;
    private final DecisionJson json;
    private final OrderGate gate;
    private final PaperExecution paper;
    private final LedgerReadModel ledger;
    private final BotAudit audit;
    private final CycleFailureRecorder failures;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final CycleTimings timings;
    private RealCycle realCycle;

    /** 261113-trading-real-bot U4 (F1): the REAL path of the cycle, for the bots of the market REAL. */
    @org.springframework.beans.factory.annotation.Autowired
    void useRealCycle(RealCycle realCycle) {
        this.realCycle = realCycle;
    }

    /**
     * 261121-paper-real-unico U4 (F1 to F3): the step before the transaction (the checks P0 to P3, the mark M0 and the
     * read of the own holdings), the flag of the own quantity and its meters. Without them (a context without the
     * worker beans) the cycle is today's.
     */
    private OwnHoldingsStep ownHoldings;

    private OwnQuantitySell ownQuantity;
    private OwnQuantityMetrics ownMetrics;

    @org.springframework.beans.factory.annotation.Autowired
    void useOwnQuantity(OwnHoldingsStep step, OwnQuantitySell flag, OwnQuantityMetrics metrics) {
        this.ownHoldings = step;
        this.ownQuantity = flag;
        this.ownMetrics = metrics;
    }

    /** 261113-trading-real-bot U4: the test strategy of the recorded swap; exists only in the test profile. */
    private RecordedSwapStrategy recordedSwap;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useRecordedSwap(RecordedSwapStrategy recordedSwap) {
        this.recordedSwap = recordedSwap;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public CycleRunner(
            CycleAuthContext auth,
            JdbcBotRepository bots,
            JdbcCycleRepository cycles,
            JdbcDecisionRepository decisions,
            LockOrder locks,
            CycleMarketReader market,
            DecisionEngine engine,
            DecisionJson json,
            OrderGate gate,
            PaperExecution paper,
            LedgerReadModel ledger,
            BotAudit audit,
            CycleFailureRecorder failures,
            Clock clock,
            PlatformTransactionManager manager,
            Optional<CycleTimings> timings) {
        this.auth = auth;
        this.bots = bots;
        this.cycles = cycles;
        this.decisions = decisions;
        this.locks = locks;
        this.market = market;
        this.engine = engine;
        this.json = json;
        this.gate = gate;
        this.paper = paper;
        this.ledger = ledger;
        this.audit = audit;
        this.failures = failures;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.timings = timings.orElse(CycleTimings.NONE);
    }

    /**
     * Runs the cycle of {@code bot} for the slot starting at {@code slotStart}; never throws for a cycle failure. The
     * market is not known here (the callers of before the U4): the check P1 reads it from the row of the bot.
     */
    public CycleResult run(BotRef bot, Instant slotStart) {
        return run(bot, Optional.empty(), slotStart);
    }

    /**
     * 261121-paper-real-unico U4 (BR2.5, alignment A15): the cycle of a bot handed by the slot dispatcher with its
     * market; a PAPER bot never reads the flag of the U4 nor the own holdings.
     */
    public CycleResult runDispatched(DispatchedBot bot, Instant slotStart) {
        return run(bot.ref(), Optional.of(bot.market()), slotStart);
    }

    private CycleResult run(BotRef bot, Optional<BotMarket> market, Instant slotStart) {
        UUID cycleId = UUID.randomUUID();
        Stage stage = new Stage();
        String previous = MDC.get(CorrelationId.MDC_KEY);
        MDC.put(CorrelationId.MDC_KEY, cycleId.toString());
        long started = System.nanoTime();
        try {
            // U4 (F1, NFR1.4): BEFORE the transaction of the cycle, as the owner; the read opens and closes its own.
            HoldingsSnapshot snapshot = ownHoldings == null
                            || market.filter(m -> m != BotMarket.REAL).isPresent()
                    ? new HoldingsSnapshot.Skipped("P0_MARKET")
                    : auth.as(bot.owner(), () -> ownHoldings.read(bot, market, slotStart));
            CycleResult result = auth.as(
                    bot.owner(), () -> transactions.execute(status -> cycle(bot, slotStart, cycleId, stage, snapshot)));
            timings.cycle(Duration.ofNanos(System.nanoTime() - started), result);
            log.info(
                    "Bot cycle: cycleId={}, botId={}, slotStart={}, result={}, action={}, outcome={}",
                    cycleId,
                    bot.botId(),
                    slotStart,
                    result.kind(),
                    result.action().orElse("-"),
                    result.outcome().or(result::skipReason).orElse("-"));
            return result;
        } catch (RuntimeException failure) {
            CycleCause cause = stage.cause;
            failures.record(bot, slotStart, cycleId, cause);
            log.error(
                    "Bot cycle rolled back: cycleId={}, botId={}, cause={}, error={}",
                    cycleId,
                    bot.botId(),
                    cause,
                    failure.getClass().getSimpleName());
            return CycleResult.failed(cycleId, cause.name());
        } finally {
            if (previous == null) {
                MDC.remove(CorrelationId.MDC_KEY);
            } else {
                MDC.put(CorrelationId.MDC_KEY, previous);
            }
        }
    }

    private CycleResult cycle(BotRef ref, Instant slotStart, UUID cycleId, Stage stage, HoldingsSnapshot snapshot) {
        stage.at(CycleCause.DATABASE);
        locks.applyCycleTimeouts();
        Instant now = clock.instant();
        if (!cycles.tryStart(cycleId, ref, slotStart, now)) {
            return CycleResult.alreadyProcessed();
        }
        if (now.isAfter(slotStart.plus(SLOT))) {
            return skip(cycleId, "SLOT_EXPIRED", now);
        }
        Optional<Bot> locked = locks.lockBot(ref.botId(), ref.scope());
        if (locked.isEmpty()) {
            return skip(cycleId, "NOT_ACTIVE", now);
        }
        Bot bot = locked.get();
        if (bot.status().state() == BotState.PAUSED) {
            return skip(cycleId, "PAUSED", now);
        }
        if (bot.status().state() != BotState.ACTIVE) {
            return skip(cycleId, "NOT_ACTIVE", now);
        }
        if (!bot.status().reasons().isEmpty() || bots.systemPaused()) {
            return skip(cycleId, "PAUSED", now);
        }
        long decideStarted = System.nanoTime();
        // 261113-trading-real-bot U4 (F1): a REAL bot decides on the REAL balance and positions of its bot wallet.
        // 261121-paper-real-unico U1 (BR1.9): a REAL bot has no limits of its own; the limits version of the bot is
        // read only in the PAPER branch. The REAL decision is recorded with the sentinel version 0 ("the limits of the
        // account") and the cap of a buy is the order limit of the account (C9), checked again by the REAL barrier.
        Optional<RealCycle.Inputs> real = Optional.empty();
        int version;
        Optional<BotLimits> paperLimits;
        BigDecimal maxOrderSize;
        // 261121-paper-real-unico U4 (F2, F3): only a REAL bot reads the flag; M1 is the mark of the row locked above.
        OwnHoldings own = null;
        long m0 = bot.changeSeq();
        if (bot.real()) {
            stage.at(CycleCause.EXECUTION);
            if (ownQuantity != null && ownQuantity.enabled()) {
                own = ownHoldingsOf(bot, snapshot);
                if (snapshot instanceof HoldingsSnapshot.Read read) {
                    m0 = read.m0();
                }
                real = realCycle.ownInputs(bot, bots.holdings(bot.botId()), now, own);
                real.flatMap(RealCycle.Inputs::own).ifPresent(inputs -> inputs.noOwnQuantityMints()
                        .forEach(mint -> ownMetrics.capped(SellCapOutcome.NO_OWN_QUANTITY)));
            } else {
                real = realCycle.inputs(bot, bots.holdings(bot.botId()), now);
            }
            if (real.isEmpty()) {
                return skip(cycleId, "REAL_UNAVAILABLE", now);
            }
            version = REAL_LIMITS_VERSION;
            paperLimits = Optional.empty();
            maxOrderSize = realCycle.maxOrderSize(bot, real.get());
        } else {
            version = bot.currentLimitsVersion().orElseThrow();
            BotLimits limits = bots.limits(bot.botId(), version).orElseThrow();
            paperLimits = Optional.of(limits);
            maxOrderSize = limits.maxOrderSize();
        }
        DecisionRequest request = request(bot, version, maxOrderSize, slotStart, now, stage, real);
        stage.at(CycleCause.OTHER);
        DecisionOutcome outcome = engine.decide(request);
        // Test profile only (RecordedSwapStrategy): a test bot of the recorded swap decides by its own fixed rule; the
        // open position is read in the mode of the bot only (the bot wallet for REAL, the PAPER account for PAPER).
        Optional<RecordedSwapStrategy.Decision> recorded = Optional.empty();
        if (recordedSwap != null) {
            stage.at(CycleCause.DATABASE);
            Optional<TokenId> mint = bot.selection().tokens().stream().findFirst();
            boolean holds = mint.isPresent()
                    && (real.isPresent()
                            ? real.get().raw().stream()
                                    .anyMatch(p -> p.mint().equals(mint.get().mint()))
                            : request.positions().stream()
                                    .anyMatch(p -> p.token().equals(mint.get())));
            recorded = recordedSwap.decide(bot, holds, slotStart, outcome.factors());
            if (recorded.isPresent()) {
                outcome = recorded.get().outcome();
            }
        }
        // U4 (F3, A8): the cut comes after the engine and before the decision is recorded; nothing is queued before.
        Optional<OwnSellCap.Verdict> verdict = Optional.empty();
        if (own != null) {
            verdict = Optional.of(cut(bot, outcome, own, m0));
        }
        stage.at(CycleCause.DATABASE);
        UUID decisionId = UUID.randomUUID();
        DecisionJson.Recorded recordedJson = own == null
                ? json.write(request, outcome)
                : json.write(
                        request, outcome, context(own, real.flatMap(RealCycle.Inputs::own), verdict.orElseThrow()));
        decisions.insert(new JdbcDecisionRepository.NewDecision(
                decisionId,
                cycleId,
                bot.botId(),
                bot.scope().tenantId(),
                bot.scope().ownerUserId(),
                request.strategy(),
                request.strategyVersion(),
                version,
                outcome,
                recordedJson,
                now,
                decisionMode(bot.real())));
        timings.decision(Duration.ofNanos(System.nanoTime() - decideStarted));
        String result;
        if (!outcome.isOrder()) {
            result = "NO_ORDER";
        } else if (real.isPresent()) {
            stage.at(CycleCause.RISK_GATE);
            Optional<Long> exact = recorded.flatMap(RecordedSwapStrategy.Decision::buyLamports);
            if (exact.isPresent()) {
                result = realCycle.buyExactly(
                        bot, decisionId, outcome.token().orElseThrow().mint(), exact.get(), outcome.summary());
            } else if (verdict.isPresent()) {
                result = realCycle.order(bot, decisionId, outcome, request.tokens(), real.get(), verdict.get());
            } else {
                result = realCycle.order(bot, decisionId, outcome, request.tokens(), real.get());
            }
            if ("REAL_RECORDED".equals(result) && outcome.action() == DecisionAction.BUY) {
                bots.openHolding(bot.botId(), outcome.token().orElseThrow(), now);
            }
        } else {
            result = order(bot, version, paperLimits.orElseThrow(), decisionId, outcome, now, stage);
        }
        stage.at(CycleCause.DATABASE);
        cycles.finish(
                cycleId,
                JdbcCycleRepository.Status.COMPLETED,
                Optional.of(result),
                Optional.empty(),
                Optional.of(decisionId),
                clock.instant());
        bots.renewMark(bot.botId());
        return CycleResult.completed(cycleId, outcome.action().name(), result);
    }

    /** 261113-trading-real-bot U5 (BR7.1, S25): the decision of a REAL bot is recorded REAL; every other one PAPER. */
    static String decisionMode(boolean real) {
        return real ? "REAL" : "PAPER";
    }

    /** BR4.1, BR3.7, BR4.8: the inputs of the decision, read inside the cycle transaction. */
    private DecisionRequest request(
            Bot bot,
            int version,
            BigDecimal maxOrderSize,
            Instant slotStart,
            Instant now,
            Stage stage,
            Optional<RealCycle.Inputs> real) {
        stage.at(CycleCause.EXECUTION);
        BigDecimal balance;
        List<PositionInput> positions = new ArrayList<>();
        Map<TokenId, Instant> holdings;
        if (real.isPresent()) {
            // REAL (U4): never the PAPER ledger.
            balance = real.get().balanceUsd();
            positions.addAll(real.get().positions());
            stage.at(CycleCause.DATABASE);
            holdings = bots.holdings(bot.botId());
        } else {
            List<PositionView> open = ledger.positions(bot.botId()).stream()
                    .filter(position -> position.quantity().signum() > 0)
                    .toList();
            balance = ledger.balance(bot.botId());
            stage.at(CycleCause.DATABASE);
            holdings = bots.holdings(bot.botId());
            for (PositionView position : open) {
                TokenId token = new TokenId(position.chain(), position.tokenMint());
                positions.add(new PositionInput(
                        token,
                        position.quantity(),
                        position.costBasis(),
                        holdings.getOrDefault(token, position.updatedAt())));
            }
        }
        // U4 (BR1.6, BR1.8): with the flag on, a mint held but not sellable by the bot keeps its opening record and
        // leaves the tokens of the request before the market is read; with the flag off the set is empty (today).
        Optional<OwnDecisionInputs> own = real.flatMap(RealCycle.Inputs::own);
        java.util.Set<String> held = own.map(OwnDecisionInputs::heldNotSellable).orElse(java.util.Set.of());
        holdings.keySet().stream()
                .filter(token -> positions.stream().noneMatch(p -> p.token().equals(token)))
                .filter(token -> !held.contains(token.mint()))
                .forEach(token -> bots.closeHolding(bot.botId(), token));
        stage.at(CycleCause.MARKET_DATA);
        List<TokenId> resolved = TokenSelectionResolver.resolve(
                bot.selection().tokens(),
                bot.selection().rules(),
                market.listed(bot.selection().rules(), now),
                positions.stream().map(PositionInput::token).toList(),
                now);
        List<TokenId> tokens = own.map(inputs -> inputs.requestTokens(resolved)).orElse(resolved);
        BigDecimal window = bot.parameters().get("windowMinutes");
        List<TokenInputs> inputs =
                tokens.stream().map(token -> market.read(token, window)).toList();
        return new DecisionRequest(
                bot.templateId(),
                bot.templateVersion(),
                bot.parameters(),
                version,
                maxOrderSize,
                balance,
                inputs,
                positions,
                slotStart,
                now);
    }

    /**
     * U4 (F1 step 4, BR2.3, BR2.5): what the cycle knows of the own holdings. A read gives its positions or its reason;
     * a REAL bot that runs under the lock without a read before it (the checks before the lock said it would not run)
     * is NOT_READ: no sale, READ_FAILED, and no metric of a read that never happened (alignment A12).
     */
    private OwnHoldings ownHoldingsOf(Bot bot, HoldingsSnapshot snapshot) {
        if (snapshot instanceof HoldingsSnapshot.Read read) {
            return switch (read.result()) {
                case HoldingsResult.Available available ->
                    new OwnHoldings.Known(available.holdings().stream()
                            .map(holding -> new OwnPosition(
                                    holding.mint(),
                                    holding.ownTokenBaseUnits(),
                                    holding.ownCostLamports(),
                                    holding.availableTokenBaseUnits()))
                            .toList());
                case HoldingsResult.Unavailable unavailable ->
                    new OwnHoldings.Unavailable(
                            OwnQuantityReason.valueOf(unavailable.reason().name()));
            };
        }
        log.warn(
                "event=REAL_OWN_HOLDINGS_UNAVAILABLE botId={} reason={} precheck=NOT_READ",
                bot.botId(),
                OwnQuantityReason.READ_FAILED);
        return new OwnHoldings.NotRead();
    }

    /** U4 (F3, BR1.4, BR1.5, BR3.1): the verdict of the cut of a SELL, its metric and its log line. */
    OwnSellCap.Verdict cut(Bot bot, DecisionOutcome outcome, OwnHoldings own, long m0) {
        if (outcome.action() != DecisionAction.SELL) {
            return new OwnSellCap.Verdict.NotApplicable();
        }
        String mint = outcome.token().orElseThrow().mint();
        Optional<Integer> decimals = realCycle.decimalsOf(mint);
        OwnSellCap.Verdict verdict = decimals.isEmpty()
                ? new OwnSellCap.Verdict.Unavailable(OwnQuantityReason.READ_FAILED)
                : OwnSellCap.verdict(outcome, own, m0, bot.changeSeq(), decimals.get());
        switch (verdict) {
            case OwnSellCap.Verdict.Unavailable unavailable
            when unavailable.reason() == OwnQuantityReason.STALE_SNAPSHOT -> {
                ownMetrics.capped(SellCapOutcome.STALE_SNAPSHOT);
                log.info(
                        "event=REAL_OWN_SELL_STALE botId={} markBefore={} markUnderLock={}",
                        bot.botId(),
                        m0,
                        bot.changeSeq());
            }
            case OwnSellCap.Verdict.NoOwnQuantity none -> {
                ownMetrics.capped(SellCapOutcome.NO_OWN_QUANTITY);
                log.info(
                        "event=REAL_OWN_SELL_NO_QUANTITY botId={} mint={} requestedBaseUnits={}",
                        bot.botId(),
                        mint,
                        none.requestedBaseUnits());
            }
            case OwnSellCap.Verdict.Sell sell
            when sell.capped() -> {
                ownMetrics.capped(SellCapOutcome.CAPPED);
                log.info(
                        "event=REAL_OWN_SELL_CAPPED botId={} mint={} requestedBaseUnits={} sellBaseUnits={}",
                        bot.botId(),
                        mint,
                        sell.requestedBaseUnits(),
                        sell.sellBaseUnits());
            }
            default -> {
                // An uncut sale, or an unavailable read already counted and logged by the step before the transaction.
            }
        }
        return verdict;
    }

    /** U4 (alignment A10): the note {@code context.ownQuantity} of a REAL decision with the flag on. */
    private static OwnQuantityContext context(
            OwnHoldings own, Optional<OwnDecisionInputs> inputs, OwnSellCap.Verdict verdict) {
        String read;
        Optional<String> reason;
        switch (own) {
            case OwnHoldings.Known known -> {
                read = "AVAILABLE";
                reason = Optional.empty();
            }
            case OwnHoldings.Unavailable unavailable -> {
                read = "UNAVAILABLE";
                reason = Optional.of(unavailable.reason().name());
            }
            case OwnHoldings.NotRead notRead -> {
                read = "NOT_READ";
                reason = Optional.of(OwnQuantityReason.READ_FAILED.name());
            }
        }
        Map<String, String> mints = new java.util.LinkedHashMap<>();
        inputs.ifPresent(present -> present.notes().forEach((mint, note) -> mints.put(mint, note.name())));
        OwnQuantityContext.Cap cap =
                switch (verdict) {
                    case OwnSellCap.Verdict.NotApplicable none -> OwnQuantityContext.Cap.NONE;
                    case OwnSellCap.Verdict.Sell sell ->
                        new OwnQuantityContext.Cap(
                                sell.capped() ? "CAPPED" : "NONE",
                                Optional.of(sell.requestedBaseUnits()),
                                Optional.of(sell.sellBaseUnits()));
                    case OwnSellCap.Verdict.NoOwnQuantity none ->
                        new OwnQuantityContext.Cap(
                                "NO_OWN_QUANTITY", Optional.of(none.requestedBaseUnits()), Optional.of(0L));
                    case OwnSellCap.Verdict.Unavailable unavailable ->
                        new OwnQuantityContext.Cap(
                                unavailable.reason() == OwnQuantityReason.STALE_SNAPSHOT
                                        ? "STALE_SNAPSHOT"
                                        : "UNAVAILABLE",
                                Optional.empty(),
                                Optional.empty());
                };
        return new OwnQuantityContext(read, reason, mints, cap);
    }

    /** BR3.4: the barrier of U6 and, approved, the execution of U7; the outcome of the cycle. */
    private String order(
            Bot bot,
            int version,
            BotLimits limits,
            UUID decisionId,
            DecisionOutcome outcome,
            Instant now,
            Stage stage) {
        TokenId token = outcome.token().orElseThrow();
        BigDecimal quantity = outcome.requestedQuantity().orElseThrow();
        boolean buy = outcome.action() == DecisionAction.BUY;
        stage.at(CycleCause.RISK_GATE);
        long gateStarted = System.nanoTime();
        GateResult verdict = gate.evaluate(
                new CandidateOrder(
                        bot.botId(),
                        decisionId,
                        buy ? OrderSide.BUY : OrderSide.SELL,
                        token.chain(),
                        token.mint(),
                        quantity),
                limits.toRef(version));
        if (verdict instanceof GateResult.Rejected rejected) {
            timings.barrierAndExecution(Duration.ofNanos(System.nanoTime() - gateStarted));
            applyTriggers(bot, rejected.pauseTriggers(), now, stage);
            return "REJECTED";
        }
        stage.at(CycleCause.EXECUTION);
        ExecutionResult executed = paper.execute(
                new PaperExecution.PaperOrderRequest(
                        bot.botId(),
                        decisionId,
                        buy ? PaperEnums.OrderSide.BUY : PaperEnums.OrderSide.SELL,
                        token.chain(),
                        token.mint(),
                        quantity,
                        version),
                "order:" + decisionId);
        timings.barrierAndExecution(Duration.ofNanos(System.nanoTime() - gateStarted));
        return switch (executed) {
            case ExecutionResult.Filled filled -> {
                updateHolding(bot, token, buy, now, stage);
                yield "FILLED";
            }
            case ExecutionResult.NotFilled notFilled -> "NOT_FILLED";
            case ExecutionResult.Duplicate duplicate -> "DUPLICATE";
            case ExecutionResult.Conflict conflict ->
                throw new IllegalStateException("The execution key of the decision conflicts (technical failure)");
        };
    }

    /** BR3.7: a BUY fill opens the holding; after a SELL fill, it leaves only when the position is zero. */
    private void updateHolding(Bot bot, TokenId token, boolean buy, Instant now, Stage stage) {
        stage.at(CycleCause.DATABASE);
        if (buy) {
            bots.openHolding(bot.botId(), token, now);
            return;
        }
        stage.at(CycleCause.EXECUTION);
        boolean remaining = ledger.positions(bot.botId()).stream()
                .anyMatch(position -> position.chain().equals(token.chain())
                        && position.tokenMint().equals(token.mint())
                        && position.quantity().signum() > 0);
        stage.at(CycleCause.DATABASE);
        if (!remaining) {
            bots.closeHolding(bot.botId(), token);
        }
    }

    /** BR3.4, BR2.5: each pause trigger of the barrier pauses the bot now, in the cycle transaction (actor system). */
    private void applyTriggers(Bot bot, java.util.Set<PauseTrigger> triggers, Instant now, Stage stage) {
        stage.at(CycleCause.DATABASE);
        Bot current = bot;
        for (PauseTrigger trigger : triggers) {
            PauseReason reason = PauseReason.valueOf(trigger.name());
            var change = BotStateMachine.pauseBy(current.status(), reason);
            if (!change.changed()) {
                continue;
            }
            current = current.toBuilder()
                    .status(change.after())
                    .pausedAt(now)
                    .updatedAt(now)
                    .build();
            bots.save(current);
            audit.bySystem(current.ref(), AuditActions.BOT_PAUSED, Map.of("reason", reason.name()));
        }
    }

    private CycleResult skip(UUID cycleId, String reason, Instant now) {
        cycles.finish(
                cycleId,
                JdbcCycleRepository.Status.SKIPPED,
                Optional.empty(),
                Optional.of(reason),
                Optional.empty(),
                now);
        return CycleResult.skipped(cycleId, reason);
    }

    /** Where the cycle is, for the cause of a failure (NFR9.2). */
    private static final class Stage {
        private CycleCause cause = CycleCause.DATABASE;

        void at(CycleCause next) {
            this.cause = next;
        }
    }
}
