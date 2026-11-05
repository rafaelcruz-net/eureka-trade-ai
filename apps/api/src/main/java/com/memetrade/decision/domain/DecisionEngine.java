package com.memetrade.decision.domain;

import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.riskassessment.api.AssessmentResult;
import com.memetrade.riskassessment.api.RiskBand;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The DecisionEngine (FR8.1, BR4.1 to BR4.10): a PURE, deterministic function of the {@link DecisionRequest}; no clock,
 * no database, no network and no AI model (BR4.2, AC4.5.5). Recomputing it from the recorded inputs gives the same
 * decision (NFR8.1).
 *
 * <ol>
 *   <li>Each token with an open position: the exits of {@link ExitRules} (BR4.5).
 *   <li>Each token without a position: the entry signal of the strategy (BR4.3 or BR4.4), after the data checks of
 *       BR4.1, then the risk band (BR4.7: HIGH blocks, unavailable is ASSESSMENT_UNAVAILABLE).
 *   <li>Fixed priority (BR4.8): the exit of the oldest position, then the strongest buy (tie: mint in alphabetical
 *       order) sized by {@link OrderSizer}, then HOLD with an open position, else NO_TRADE with the reason of the
 *       first token in list order that has one (NO_SIGNAL when none has).
 * </ol>
 *
 * Every evaluated token contributes its factors; a missing value is a reason and an UNKNOWN factor, never a zero.
 */
public final class DecisionEngine {

    private static final Duration SLOT = Duration.ofMinutes(5);

    public DecisionOutcome decide(DecisionRequest request) {
        var validUntil = request.slotStart().plus(SLOT);
        if (request.tokens().isEmpty()) {
            return new DecisionOutcome(
                    DecisionAction.NO_TRADE,
                    Optional.empty(),
                    Optional.of(NoTradeReason.NO_TOKENS),
                    Optional.empty(),
                    Optional.empty(),
                    List.of(),
                    DecisionText.noTrade(NoTradeReason.NO_TOKENS, Optional.empty()),
                    validUntil);
        }
        Map<TokenId, PositionInput> positions = new HashMap<>();
        request.positions().forEach(position -> positions.put(position.token(), position));
        List<TokenEvaluation> evaluations = new ArrayList<>();
        List<Factor> factors = new ArrayList<>();
        for (TokenInputs inputs : request.tokens()) {
            TokenEvaluation evaluation = evaluate(inputs, positions.get(inputs.token()), request);
            evaluations.add(evaluation);
            factors.addAll(evaluation.factors());
        }
        return choose(request, evaluations, positions, factors);
    }

    private static TokenEvaluation evaluate(TokenInputs inputs, PositionInput position, DecisionRequest request) {
        WindowReading window = new WindowReading(inputs.window(), request.slotStart());
        if (position != null) {
            return ExitRules.evaluate(inputs, position, window, request);
        }
        TokenEvaluation signal = request.strategy() == StrategyId.MOMENTUM_VOLUME
                ? MomentumVolumeStrategy.evaluate(inputs, window, request)
                : RangeBreakoutStrategy.evaluate(inputs, window, request);
        if (signal.reason().isPresent()) {
            return signal;
        }
        Optional<NoTradeReason> buyData = buyDataReason(inputs, request);
        List<Factor> factors = new ArrayList<>(signal.factors());
        factors.add(riskFactor(inputs));
        if (buyData.isPresent()) {
            return TokenEvaluation.nothing(inputs.token(), factors, buyData);
        }
        if (signal.strength().isPresent()
                && inputs.assessment() instanceof AssessmentResult.Available available
                && available.band() == RiskBand.HIGH) {
            return TokenEvaluation.nothing(inputs.token(), factors, Optional.of(NoTradeReason.RISK_BAND_HIGH));
        }
        return new TokenEvaluation(inputs.token(), factors, signal.strength(), Optional.empty(), Optional.empty());
    }

    /** BR4.1 for a buy: the reference price and the places of the mint are known and fresh; the assessment exists. */
    private static Optional<NoTradeReason> buyDataReason(TokenInputs inputs, DecisionRequest request) {
        if (!inputs.referencePrice().isKnown() || !inputs.decimals().isKnown()) {
            return Optional.of(NoTradeReason.UNKNOWN_DATA);
        }
        if (((MarketValue.Known) inputs.referencePrice()).value().signum() <= 0) {
            return Optional.of(NoTradeReason.UNKNOWN_DATA);
        }
        if (!WindowReading.fresh(inputs.referencePrice(), request.now())) {
            return Optional.of(NoTradeReason.STALE_DATA);
        }
        if (inputs.assessment() instanceof AssessmentResult.Unavailable) {
            return Optional.of(NoTradeReason.ASSESSMENT_UNAVAILABLE);
        }
        return Optional.empty();
    }

    private static Factor riskFactor(TokenInputs inputs) {
        if (inputs.assessment() instanceof AssessmentResult.Available available) {
            return new Factor(
                    inputs.token(),
                    "riskBand",
                    FactorValue.of(BigDecimal.valueOf(available.score())),
                    Optional.empty(),
                    available.band() != RiskBand.HIGH,
                    Optional.of(available.band().name()));
        }
        return new Factor(inputs.token(), "riskBand", FactorValue.unknown(), Optional.empty(), false, Optional.empty());
    }

    private static DecisionOutcome choose(
            DecisionRequest request,
            List<TokenEvaluation> evaluations,
            Map<TokenId, PositionInput> positions,
            List<Factor> factors) {
        var validUntil = request.slotStart().plus(SLOT);
        Optional<TokenEvaluation> exit = evaluations.stream()
                .filter(e -> e.exit().isPresent())
                .min(Comparator.comparing(
                                (TokenEvaluation e) -> positions.get(e.token()).openedAt())
                        .thenComparing(e -> e.token().mint()));
        if (exit.isPresent()) {
            return sell(exit.get(), positions.get(exit.get().token()), factors, validUntil);
        }
        Optional<TokenEvaluation> buy = evaluations.stream()
                .filter(e -> e.strength().isPresent())
                .min(Comparator.comparing((TokenEvaluation e) -> e.strength().orElseThrow(), Comparator.reverseOrder())
                        .thenComparing(e -> e.token().mint()));
        if (buy.isPresent()) {
            return buy(request, buy.get(), factors, validUntil);
        }
        Optional<TokenId> held = request.tokens().stream()
                .map(TokenInputs::token)
                .filter(positions::containsKey)
                .findFirst();
        if (held.isPresent()) {
            return new DecisionOutcome(
                    DecisionAction.HOLD,
                    held,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    factors,
                    DecisionText.hold(held.get()),
                    validUntil);
        }
        Optional<TokenEvaluation> withReason =
                evaluations.stream().filter(e -> e.reason().isPresent()).findFirst();
        NoTradeReason reason = withReason.flatMap(TokenEvaluation::reason).orElse(NoTradeReason.NO_SIGNAL);
        TokenId token = withReason
                .map(TokenEvaluation::token)
                .orElse(evaluations.getFirst().token());
        return noTrade(reason, token, factors, validUntil);
    }

    private static DecisionOutcome sell(
            TokenEvaluation exit, PositionInput position, List<Factor> factors, java.time.Instant validUntil) {
        if (position == null) {
            return noTrade(NoTradeReason.NO_POSITION_TO_SELL, exit.token(), factors, validUntil);
        }
        ExitReason reason = exit.exit().orElseThrow();
        return new DecisionOutcome(
                DecisionAction.SELL,
                Optional.of(exit.token()),
                Optional.empty(),
                Optional.of(reason),
                Optional.of(position.quantity()),
                factors,
                DecisionText.sell(exit.token(), reason, exit.factors()),
                validUntil);
    }

    private static DecisionOutcome buy(
            DecisionRequest request, TokenEvaluation chosen, List<Factor> factors, java.time.Instant validUntil) {
        TokenInputs inputs = request.tokens().stream()
                .filter(t -> t.token().equals(chosen.token()))
                .findFirst()
                .orElseThrow();
        BigDecimal price = ((MarketValue.Known) inputs.referencePrice()).value();
        int decimals = ((MarketValue.Known) inputs.decimals()).value().intValueExact();
        BigDecimal quantity = OrderSizer.quantity(
                request.availableBalance(),
                request.parameter("buyFractionPct"),
                request.maxOrderSize(),
                price,
                decimals);
        if (quantity.signum() <= 0) {
            return noTrade(NoTradeReason.QUANTITY_BELOW_MINIMUM, chosen.token(), factors, validUntil);
        }
        return new DecisionOutcome(
                DecisionAction.BUY,
                Optional.of(chosen.token()),
                Optional.empty(),
                Optional.empty(),
                Optional.of(quantity),
                factors,
                DecisionText.buy(
                        request.strategy(), chosen.token(), chosen.factors(), request.parameter("windowMinutes")),
                validUntil);
    }

    private static DecisionOutcome noTrade(
            NoTradeReason reason, TokenId token, List<Factor> factors, java.time.Instant validUntil) {
        return new DecisionOutcome(
                DecisionAction.NO_TRADE,
                Optional.of(token),
                Optional.of(reason),
                Optional.empty(),
                Optional.empty(),
                factors,
                DecisionText.noTrade(reason, Optional.of(token)),
                validUntil);
    }
}
