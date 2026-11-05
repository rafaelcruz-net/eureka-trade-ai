package com.memetrade.riskcontrol;

import static com.memetrade.riskcontrol.RiskGateFixtures.baseLimits;
import static com.memetrade.riskcontrol.RiskGateFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.PaperEnums;
import com.memetrade.ledger.api.PaperExecution.ExecutionResult;
import com.memetrade.ledger.api.PaperExecution.PaperOrderRequest;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.riskcontrol.api.CandidateOrder;
import com.memetrade.riskcontrol.api.GateResult;
import com.memetrade.riskcontrol.api.OrderGate;
import com.memetrade.riskcontrol.api.OrderSide;
import com.memetrade.riskcontrol.infra.RiskEvaluationPurgeJob;
import com.memetrade.testsupport.AbstractU6IT;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 11 of U6, WRITTEN BEFORE THE CODE (security-design.md "Decisão antiga reavaliada"; NFR10.7): once the purge
 * deleted the approved evaluation of a decision older than 30 days, U6 no longer recognizes that decision; the second
 * execution is still contained by the idempotency key of U7, which answers Duplicate, and there is no second order.
 */
class PurgedDecisionReplayIT extends AbstractU6IT {

    @Autowired
    private OrderGate gate;

    @Autowired
    private RiskEvaluationPurgeJob purge;

    @Test
    void aDecisionEvaluatedAgainAfterThePurgeIsContainedByTheIdempotencyOfU7() {
        Principal person = newPerson();
        TokenId token = newToken();
        // The cycle happened 32 days ago in database time (the purge cut is the database now()).
        Instant databaseNow =
                jdbc.sql("select now()").query(Timestamp.class).single().toInstant();
        clock.set(databaseNow.minus(Duration.ofDays(32)));
        marketNow(token, "1.000000000000", "10000.000000");
        UUID bot = fundedBot(person, "1000.000000");
        CandidateOrder candidate = order(bot, token, OrderSide.BUY, "10.000000");
        String key = "decision:" + candidate.decisionId();
        PaperOrderRequest request = new PaperOrderRequest(
                bot,
                candidate.decisionId(),
                PaperEnums.OrderSide.BUY,
                token.chain(),
                token.mint(),
                candidate.requestedQuantity(),
                1);
        assertThat(inCycle(person, () -> gate.evaluate(candidate, baseLimits())))
                .isInstanceOf(GateResult.Approved.class);
        assertThat(inCycle(person, () -> paperExecution.execute(request, key)))
                .isInstanceOf(ExecutionResult.Filled.class);

        clock.set(databaseNow);
        purge.runOnce();
        assertThat(RiskGateFixtures.evaluations(jdbc, candidate.decisionId())).isZero();

        // Known limit: the barrier sees a first evaluation again...
        marketNow(token, "1.000000000000", "10000.000000");
        GateResult again = inCycle(person, () -> gate.evaluate(candidate, baseLimits()));
        assertThat(again).isInstanceOf(GateResult.Approved.class);
        // ...and the execution with the same key returns the original result, without a second order.
        ExecutionResult second = inCycle(person, () -> paperExecution.execute(request, key));
        assertThat(second).isInstanceOf(ExecutionResult.Duplicate.class);
        assertThat(jdbc.sql("select count(*) from paper_order where bot_id = :b")
                        .param("b", bot)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }
}
