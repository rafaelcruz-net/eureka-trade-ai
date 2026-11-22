package com.memetrade.bots.worker;

import com.memetrade.bots.application.RealDecisionStep;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.RealSizing;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.decision.domain.DecisionAction;
import com.memetrade.decision.domain.DecisionOutcome;
import com.memetrade.decision.domain.PositionInput;
import com.memetrade.decision.domain.TokenInputs;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.ledger.api.RealLedgerView;
import com.memetrade.ledger.api.RealPosition;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.riskcontrol.api.RealAccountLimits;
import com.memetrade.riskcontrol.api.RealAccountLimitsView;
import com.memetrade.riskcontrol.api.RealLimitsUnavailable;
import com.memetrade.shared.config.ProcessRole;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 261113-trading-real-bot U4 (F1, BR1.2): the REAL inputs and the REAL order of the cycle of a REAL bot. The decision
 * engine sees the REAL balance and positions of the bot wallet (M1, M2) in dollars and whole tokens, priced with the
 * quote of the wrapped SOL (M4); the order goes to {@link RealDecisionStep} in lamports and base units. Nothing of the
 * PAPER ledger is read nor written. An unknown price is never zero: without the price of SOL there is no REAL input.
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
public class RealCycle {

    static final String WRAPPED_SOL = "So11111111111111111111111111111111111111112";

    private static final Logger log = LoggerFactory.getLogger(RealCycle.class);

    private final RealStateQuery states;
    private final RealLedger ledger;
    private final MarketDataQuery market;
    private final RealDecisionStep step;
    private final RealAccountLimits accountLimits;
    private final TransactionTemplate savepoint;

    public RealCycle(
            RealStateQuery states,
            RealLedger ledger,
            MarketDataQuery market,
            RealDecisionStep step,
            RealAccountLimits accountLimits,
            PlatformTransactionManager transactions) {
        this.states = states;
        this.ledger = ledger;
        this.market = market;
        this.step = step;
        this.accountLimits = accountLimits;
        this.savepoint = new TransactionTemplate(transactions);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /**
     * 261121-paper-real-unico U1 (BR1.9; reliability-design.md "Decisão do fundador"): the cap of a buy of a REAL bot,
     * in dollars: the order limit of the account (C9), {@code floor(cap x orderMaxBps / 10000)} lamports, at the SOL of
     * this cycle, rounded down ({@link RealSizing#dollarsOfLamports}). Zero without a cap, without an ACTIVE wallet, or
     * when C9 fails, an overflow of its exact arithmetic included (logged, the cycle goes on): with zero the sizer gives QUANTITY_BELOW_MINIMUM, so no buy; the sells
     * do not use this cap. The read runs in a savepoint, so a failed read never aborts the cycle transaction.
     */
    BigDecimal maxOrderSize(Bot bot, Inputs inputs) {
        Optional<RealAccountLimitsView> limits;
        try {
            limits = savepoint.execute(status ->
                    accountLimits.read(bot.scope().tenantId(), bot.scope().ownerUserId()));
        } catch (RealLimitsUnavailable | org.springframework.dao.DataAccessException | ArithmeticException failure) {
            // Review R-03: any failure of C9, an overflow of the exact arithmetic included, fails closed (no buy).
            log.warn(
                    "REAL account limits unavailable in the cycle: cause={}",
                    failure.getClass().getSimpleName());
            return NO_BUY;
        }
        return orderMaxUsd(limits, inputs.solUsd());
    }

    /**
     * The cap of a buy decided by the founder for a REAL bot without an order limit of the account: no buy at all. It
     * is a decided cap, not an unknown value standing as zero (the price of SOL, when unknown, skips the cycle).
     */
    static final BigDecimal NO_BUY = BigDecimal.valueOf(0, 6);

    /** The order limit of the account in dollars; {@link #NO_BUY} when it is not defined or there is no wallet. */
    static BigDecimal orderMaxUsd(Optional<RealAccountLimitsView> limits, BigDecimal solUsd) {
        if (limits.isEmpty() || !(limits.get().orderMax() instanceof RealAccountLimitsView.Amount.Known known)) {
            return NO_BUY;
        }
        return RealSizing.dollarsOfLamports(known.lamports(), solUsd);
    }

    /** The balance in dollars and the open positions of the bot wallet of the owner. */
    record Inputs(BigDecimal balanceUsd, List<PositionInput> positions, List<RealPosition> raw, BigDecimal solUsd) {}

    Optional<Inputs> inputs(Bot bot, Map<TokenId, Instant> holdings, Instant now) {
        Optional<RealStateView> state =
                states.readRealState(bot.scope().tenantId(), bot.scope().ownerUserId());
        Optional<BigDecimal> sol = known(market.referencePrice(new TokenId("solana", WRAPPED_SOL)));
        if (state.isEmpty() || sol.isEmpty()) {
            return Optional.empty();
        }
        RealLedgerView view = ledger.view(bot.scope().tenantId(), state.get().botWalletId(), Optional.empty());
        long operable = view.balance()
                .operableLamports(state.get().capLamports(), state.get().feeReserveLamports());
        List<PositionInput> positions = new ArrayList<>();
        for (RealPosition position : view.positions()) {
            TokenId token = new TokenId("solana", position.mint());
            Optional<BigDecimal> decimals = known(market.decimals(token));
            if (decimals.isEmpty()) {
                continue;
            }
            positions.add(new PositionInput(
                    token,
                    RealSizing.tokens(position.tokenBaseUnits(), decimals.get().intValueExact()),
                    RealSizing.dollarsOfLamports(position.costLamports(), sol.get()),
                    holdings.getOrDefault(token, now)));
        }
        return Optional.of(
                new Inputs(RealSizing.dollarsOfLamports(operable, sol.get()), positions, view.positions(), sol.get()));
    }

    /** The REAL order of a BUY or SELL decision; the outcome of the cycle. */
    String order(Bot bot, UUID decisionId, DecisionOutcome outcome, List<TokenInputs> tokens, Inputs inputs) {
        TokenId token = outcome.token().orElseThrow();
        String reason = outcome.summary();
        RealDecisionStep.Candidate candidate;
        if (outcome.action() == DecisionAction.BUY) {
            Optional<BigDecimal> price = tokens.stream()
                    .filter(t -> t.token().equals(token))
                    .findFirst()
                    .flatMap(t -> known(t.referencePrice()));
            Optional<Long> lamports = RealSizing.buyLamports(
                    outcome.requestedQuantity().orElseThrow(), price, Optional.of(inputs.solUsd()));
            if (lamports.isEmpty()) {
                return "NO_ORDER";
            }
            candidate = RealDecisionStep.Candidate.buy(
                    bot.scope().tenantId(),
                    bot.scope().ownerUserId(),
                    bot.botId(),
                    decisionId,
                    token.mint(),
                    lamports.get(),
                    reason);
        } else {
            Optional<RealPosition> position = inputs.raw().stream()
                    .filter(p -> p.mint().equals(token.mint()))
                    .findFirst();
            if (position.isEmpty()) {
                return "NO_ORDER";
            }
            candidate = RealDecisionStep.Candidate.sell(
                    bot.scope().tenantId(),
                    bot.scope().ownerUserId(),
                    bot.botId(),
                    decisionId,
                    token.mint(),
                    position.get().tokenBaseUnits(),
                    reason);
        }
        return "REAL_" + step.submit(candidate).result();
    }

    /**
     * A REAL buy of exactly {@code lamports} of {@code mint} (the test strategy of the recorded swap, whose quote is for
     * one fixed amount); the same barrier and the same M6 as {@link #order}. The outcome of the cycle.
     */
    String buyExactly(Bot bot, UUID decisionId, String mint, long lamports, String reason) {
        return "REAL_"
                + step.submit(RealDecisionStep.Candidate.buy(
                                bot.scope().tenantId(),
                                bot.scope().ownerUserId(),
                                bot.botId(),
                                decisionId,
                                mint,
                                lamports,
                                reason))
                        .result();
    }

    private static Optional<BigDecimal> known(MarketValue value) {
        return value instanceof MarketValue.Known known ? Optional.of(known.value()) : Optional.empty();
    }
}
