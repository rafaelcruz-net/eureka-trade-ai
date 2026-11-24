package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.application.RealDecisionStep;
import com.memetrade.realexecution.OpenSellsFixture;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U4, Etapa 5, T11 (written before the code; BR3.3, AC6.1.4; team.md "uma única transação por
 * ordem REAL"): the SELL the cut recorded is processed again by the queue, submitted again with the same decision (the
 * same Idempotency-Key of its order) and retried after its order became AMBIGUOUS: one transaction only. A second
 * decision of a bot with an order in progress is refused ORDER_IN_PROGRESS, as today.
 */
@Tag("u4-own-quantity-cycle")
class OwnQuantityOneTransactionIT extends AbstractOwnQuantityIT {

    @Test
    void theCutSaleIsSentOnceWhateverIsRepeated() {
        Pair pair = pair();
        // The engine asks for more than A owns (defence in depth): the cut records 100 XYZ.
        OwnQuantityCycle cycle = cycle(true).sells(pair.xyz(), "150");
        cycle.run(pair.refA(), slot());
        assertThat(sellDecisions(pair.a())).containsExactly(A_UNITS);
        assertThat(cycle.meters
                        .get("real_cycle_sell_capped")
                        .tag("outcome", "CAPPED")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        UUID request = pendingSellOf(pair.a());
        UUID decision = jdbc.sql("select decision_id from real_decision_request where decision_request_id = :r")
                .param("r", request)
                .query(UUID.class)
                .single();

        consumer.runOnce();
        consumer.runOnce();
        UUID order = orderOf(request);
        driver.runOnce();
        driver.runOnce();
        RealDecisionStep.Outcome again =
                transactions.execute(status -> decisionStep.submit(RealDecisionStep.Candidate.sell(
                        pair.tenantId(), pair.userId(), pair.a(), decision, pair.xyz(), A_UNITS, "de novo")));
        clock.advance(Duration.ofSeconds(60));
        confirmation.runOnce();
        assertThat(state(order)).isEqualTo("AMBIGUOUS");
        ambiguous.runOnce();
        driver.runOnce();
        consumer.runOnce();

        assertThat(again.result()).isEqualTo("ORDER_IN_PROGRESS");
        assertThat(jdbc.sql("select count(*) from real_order where idempotency_key = :key")
                        .param("key", request.toString())
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(chain.totalSends()).isEqualTo(1);
    }

    @Test
    void aBotWithAnOrderInProgressRefusesTheCutSale() {
        Pair pair = pair();
        new OpenSellsFixture(jdbc)
                .order(pair.tenantId(), pair.userId(), pair.b(), "BUY", token("0.0001", 6, 5), 1_000L, "SENT");
        OwnQuantityCycle cycle = cycle(true).sellsAll(pair.xyz());

        CycleResult result = cycle.run(pair.refB(), slot());

        assertThat(outcome(result)).isEqualTo("REAL_ORDER_IN_PROGRESS");
        assertThat(sellDecisions(pair.b())).isEmpty();
    }
}
