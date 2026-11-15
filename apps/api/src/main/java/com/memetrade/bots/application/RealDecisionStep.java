package com.memetrade.bots.application;

import com.memetrade.bots.api.RealBotOwnership;
import com.memetrade.realexecution.api.RealDecision;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.realexecution.api.RealSide;
import com.memetrade.riskcontrol.api.GateDecision;
import com.memetrade.riskcontrol.api.OrderIntent;
import com.memetrade.riskcontrol.api.RealGateUnavailable;
import com.memetrade.riskcontrol.api.RealRiskGate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 261113-trading-real-bot U4 (F1, BR1.2, BR2.2, BR8.1; adendo da U3 "Posse do bot"): the REAL path of the cycle of a
 * bot, in the transaction of the cycle ({@code MANDATORY}):
 *
 * <ol>
 *   <li>an order in progress of the bot discards the decision (ORDER_IN_PROGRESS) before the barrier;
 *   <li>an active liquidation of the person discards it (LIQUIDATION_ACTIVE);
 *   <li>the bot must be a REAL bot of this tenant and person (the precondition of M5);
 *   <li>the barrier (M5 {@code evaluate}) in the same transaction; a refusal records nothing in the execution;
 *   <li>approved, M6 {@code recordDecision} records the PENDING decision.
 * </ol>
 *
 * A barrier without its lock answers UNAVAILABLE (the transaction stays usable, the cycle records nothing); after a
 * SQL failure ({@link RealGateUnavailable#transactionUsable()} false) the exception goes up and the cycle rolls back.
 * Nothing here ever writes the PAPER ledger.
 */
@Component
public class RealDecisionStep {

    private static final Logger log = LoggerFactory.getLogger(RealDecisionStep.class);

    private final RealExecutionPort execution;
    private final RealRiskGate gate;
    private final RealBotOwnership bots;

    public RealDecisionStep(RealExecutionPort execution, RealRiskGate gate, RealBotOwnership bots) {
        this.execution = execution;
        this.gate = gate;
        this.bots = bots;
    }

    /** One decision of the cycle of a REAL bot: tenant and person read from the bot row. */
    public record Candidate(
            UUID tenantId,
            UUID userId,
            UUID botId,
            UUID decisionId,
            RealSide side,
            String mint,
            Long inputLamports,
            Long tokenBaseUnits,
            String reason) {

        public Candidate {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(botId, "botId");
            Objects.requireNonNull(decisionId, "decisionId");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(mint, "mint");
            Objects.requireNonNull(reason, "reason");
        }

        public static Candidate buy(
                UUID tenantId, UUID userId, UUID botId, UUID decisionId, String mint, long lamports, String reason) {
            return new Candidate(tenantId, userId, botId, decisionId, RealSide.BUY, mint, lamports, null, reason);
        }

        public static Candidate sell(
                UUID tenantId, UUID userId, UUID botId, UUID decisionId, String mint, long units, String reason) {
            return new Candidate(tenantId, userId, botId, decisionId, RealSide.SELL, mint, null, units, reason);
        }
    }

    /**
     * @param result RECORDED, ORDER_IN_PROGRESS, LIQUIDATION_ACTIVE, BOT_NOT_OWNED, REJECTED or UNAVAILABLE
     */
    public record Outcome(String result, Optional<UUID> decisionRequestId) {
        static Outcome of(String result) {
            return new Outcome(result, Optional.empty());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RealGateUnavailable.class)
    public Outcome submit(Candidate candidate) {
        if (execution.hasOrderInProgress(candidate.tenantId(), candidate.botId())) {
            return Outcome.of("ORDER_IN_PROGRESS");
        }
        if (execution.liquidationActive(candidate.tenantId(), candidate.userId())) {
            return Outcome.of("LIQUIDATION_ACTIVE");
        }
        if (!bots.activeRealBotOf(candidate.tenantId(), candidate.userId(), candidate.botId())) {
            return Outcome.of("BOT_NOT_OWNED");
        }
        OrderIntent intent = candidate.side() == RealSide.BUY
                ? OrderIntent.botBuy(
                        candidate.tenantId(),
                        candidate.userId(),
                        candidate.botId(),
                        candidate.decisionId(),
                        candidate.mint(),
                        candidate.inputLamports())
                : OrderIntent.botSell(
                        candidate.tenantId(),
                        candidate.userId(),
                        candidate.botId(),
                        candidate.decisionId(),
                        candidate.mint(),
                        candidate.tokenBaseUnits());
        GateDecision decision;
        try {
            decision = gate.evaluate(intent);
        } catch (RealGateUnavailable unavailable) {
            if (!unavailable.transactionUsable()) {
                throw unavailable;
            }
            log.warn(
                    "REAL barrier unavailable in the cycle: dependency={}",
                    unavailable.dependency().label());
            return Outcome.of("UNAVAILABLE");
        }
        if (!(decision instanceof GateDecision.Approved approved)) {
            return Outcome.of("REJECTED");
        }
        UUID requestId = execution.recordDecision(new RealDecision(
                candidate.tenantId(),
                candidate.userId(),
                candidate.botId(),
                candidate.decisionId(),
                approved.approvalId(),
                candidate.side(),
                candidate.mint(),
                candidate.inputLamports(),
                candidate.tokenBaseUnits(),
                candidate.reason()));
        return new Outcome("RECORDED", Optional.of(requestId));
    }
}
