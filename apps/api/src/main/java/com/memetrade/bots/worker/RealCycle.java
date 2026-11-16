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
import com.memetrade.shared.config.ProcessRole;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

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

    private final RealStateQuery states;
    private final RealLedger ledger;
    private final MarketDataQuery market;
    private final RealDecisionStep step;

    public RealCycle(RealStateQuery states, RealLedger ledger, MarketDataQuery market, RealDecisionStep step) {
        this.states = states;
        this.ledger = ledger;
        this.market = market;
        this.step = step;
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
