package com.memetrade.riskcontrol.domain;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.api.PortfolioRiskView;
import com.memetrade.pnl.api.PortfolioRiskView.RiskSnapshot;
import com.memetrade.riskcontrol.api.BotLimitsVersionRef;
import com.memetrade.riskcontrol.api.CandidateOrder;
import com.memetrade.riskcontrol.api.GateResult;
import com.memetrade.riskcontrol.api.OrderGate;
import com.memetrade.riskcontrol.domain.EvaluationErrors.Cause;
import com.memetrade.riskcontrol.domain.EvaluationRecord.Outcome;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * F1 and F2 of the barrier (C14), pure of Spring: it runs inside the cycle transaction that the caller holds
 * ({@code TransactionalOrderGate} requires it). Fail closed (NFR10.2): the only way to {@link GateResult.Approved} is
 * an evaluation with no failed reason that was recorded; every exception (C11, C13, database, timeout) is counted with
 * its cause, logged and thrown again unchanged, never turned into an approval nor a rejection, and never retried
 * (NFR10.4). Nothing is kept between calls (NFR10.5).
 */
public final class OrderGateService implements OrderGate {

    private static final Logger log = LoggerFactory.getLogger(OrderGateService.class);

    private final MarketDataQuery market;
    private final PortfolioRiskView portfolio;
    private final EvaluationRepository evaluations;
    private final RejectionRepository rejections;
    private final RejectionEvents events;
    private final AuthContext auth;
    private final Clock clock;
    private final LimitChecks checks;
    private final EvaluationErrors errors;

    public OrderGateService(
            MarketDataQuery market,
            PortfolioRiskView portfolio,
            EvaluationRepository evaluations,
            RejectionRepository rejections,
            RejectionEvents events,
            AuthContext auth,
            Clock clock,
            LimitChecks checks,
            EvaluationErrors errors) {
        this.market = market;
        this.portfolio = portfolio;
        this.evaluations = evaluations;
        this.rejections = rejections;
        this.events = events;
        this.auth = auth;
        this.clock = clock;
        this.checks = checks;
        this.errors = errors;
    }

    @Override
    public GateResult evaluate(CandidateOrder order, BotLimitsVersionRef limits) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(limits, "limits");
        Step step = new Step();
        try {
            return evaluate(order, limits, step);
        } catch (RuntimeException failure) {
            errors.count(step.cause);
            log.error(
                    "Order evaluation failed: cause={}, botId={}, decisionId={}",
                    step.cause,
                    order.botId(),
                    order.decisionId(),
                    failure);
            throw failure;
        }
    }

    private GateResult evaluate(CandidateOrder order, BotLimitsVersionRef limits, Step step) {
        // BR1.1: tenant and person only from the authentication context of the cycle.
        Principal person = auth.current();
        Instant now = clock.instant();
        TokenId token = new TokenId(order.chain(), order.tokenMint());

        // BR1.3: one reading of C11 and one of C13, also when the call turns out to be a duplicate.
        step.cause = Cause.MARKET_DATA;
        MarketValue price = market.referencePrice(token);
        MarketValue liquidity = market.liquidity(token);
        step.cause = Cause.PORTFOLIO;
        RiskSnapshot risk = portfolio.snapshot(
                order.botId(), order.chain(), order.tokenMint(), LocalDate.ofInstant(now, ZoneOffset.UTC));
        GateInputs inputs = new GateInputs(order, limits, price, liquidity, risk, now);

        // F1 step 4 / F2: an earlier evaluation of the same decision makes this call a duplicate.
        step.cause = Cause.DATABASE;
        var earlier = evaluations.find(person.tenantId(), order.botId(), order.decisionId());
        if (earlier.isPresent()) {
            return duplicate(person, inputs, earlier.get().evaluationId());
        }

        step.cause = Cause.OTHER;
        CheckOutcome outcome = checks.evaluate(inputs);

        // BR3.4, BR2.8: the evaluation is recorded first, with an insert that never aborts the transaction.
        step.cause = Cause.DATABASE;
        EvaluationRecord evaluation = new EvaluationRecord(
                UUID.randomUUID(),
                person.tenantId(),
                person.userId(),
                order.botId(),
                order.decisionId(),
                order.side(),
                order.chain(),
                order.tokenMint(),
                order.requestedQuantity(),
                limits.version(),
                outcome.approved() ? Outcome.APPROVED : Outcome.REJECTED,
                now);
        if (!evaluations.insertIfAbsent(evaluation)) {
            // A concurrent transaction recorded the same decision and committed: the computed result is dropped.
            UUID winner = evaluations
                    .find(person.tenantId(), order.botId(), order.decisionId())
                    .orElseThrow(
                            () -> new IllegalStateException(
                                    "The evaluation that won the conflict is not visible; the cycle must run in READ COMMITTED"))
                    .evaluationId();
            return duplicate(person, inputs, winner);
        }
        if (outcome.approved()) {
            log.debug(
                    "Order approved: evaluationId={}, botId={}, decisionId={}",
                    evaluation.evaluationId(),
                    order.botId(),
                    order.decisionId());
            return new GateResult.Approved(order);
        }
        return reject(person, inputs, evaluation.evaluationId(), outcome);
    }

    /** F2 steps 2 to 4: only DUPLICATE, citing the original evaluation, with the market data of this call. */
    private GateResult duplicate(Principal person, GateInputs inputs, UUID originalEvaluationId) {
        return reject(person, inputs, originalEvaluationId, checks.duplicate(inputs, originalEvaluationId));
    }

    /** BR3.5: the rejection, one detail per reason and one ORDER_REJECTED, in the cycle transaction. */
    private GateResult reject(Principal person, GateInputs inputs, UUID evaluationId, CheckOutcome outcome) {
        CandidateOrder order = inputs.order();
        RejectionRecord rejection = new RejectionRecord(
                UUID.randomUUID(),
                person.tenantId(),
                person.userId(),
                order.botId(),
                order.decisionId(),
                evaluationId,
                order.side(),
                order.chain(),
                order.tokenMint(),
                order.requestedQuantity(),
                outcome.details(),
                outcome.pauseTriggers(),
                inputs.limits().version(),
                new MarketInputs(
                        inputs.referencePrice(),
                        inputs.liquidity(),
                        outcome.estimatedSlippagePct(),
                        outcome.orderValue()),
                inputs.now());
        rejections.insert(rejection);
        events.rejected(rejection);
        log.info(
                "Order rejected: rejectionId={}, botId={}, decisionId={}, mainReason={}, reasons={}, pauseTriggers={}",
                rejection.rejectionId(),
                order.botId(),
                order.decisionId(),
                rejection.reasons().getFirst(),
                rejection.reasons().size(),
                rejection.pauseTriggers());
        return new GateResult.Rejected(
                rejection.rejectionId(), rejection.reasons(), inputs.limits().version(), rejection.pauseTriggers());
    }

    /** The cause a failure is counted under: the step that was running when it happened. */
    private static final class Step {
        private Cause cause = Cause.OTHER;
    }
}
