package com.memetrade.realexecution.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.api.RealChain;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.testsupport.SyntheticIds;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 12 of the U4, written BEFORE the code (F8, BR8.1, BR8.2; S19, S20, S26, S30): "vender tudo e sacar" creates the
 * liquidation and cancels the orders not sent, in one transaction under the lock of the person; the sales are created
 * one at a time, after the orders already sent end, with the quantity of the position at that moment; then the
 * withdrawal of everything free, DONE when every sale confirmed, PARTIAL with what stayed and why.
 */
@Tag("u4-real-execution")
class LiquidationIT extends AbstractRealExecutionIT {

    @Autowired
    private LiquidationService liquidationService;

    private static Principal person(Setup setup) {
        return new Principal(setup.tenantId(), setup.userId(), Role.BETA_USER);
    }

    private String liquidationState(UUID id) {
        return jdbc.sql("select state from real_liquidation where liquidation_id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private List<UUID> sales(UUID liquidationId) {
        return jdbc.sql("select real_order_id from real_order where liquidation_id = :id order by created_at")
                .param("id", liquidationId)
                .query(UUID.class)
                .list();
    }

    /** One round of the worker for the sale of {@code units}: the sale is created, sent and confirmed. */
    private UUID sellOne(UUID liquidationId, long units, boolean fails) {
        List<UUID> before = sales(liquidationId);
        liquidations.runOnce();
        List<UUID> all = sales(liquidationId);
        assertThat(all).hasSize(before.size() + 1);
        // The new sale, not the last by created_at: the test clock stands still, so two sales share the instant.
        UUID sale = all.stream().filter(id -> !before.contains(id)).findFirst().orElseThrow();
        driver.runOnce();
        assertThat(state(sale)).isEqualTo("SENT");
        // The job picks the positions in its own order (random mints): the effect sells what this sale offers.
        long offered = jdbc.sql("select input_base_units from real_order where real_order_id = :id")
                .param("id", sale)
                .query(Long.class)
                .single();
        assertThat(offered)
                .as("a single position is sold whole")
                .isIn(units, 5_000L, 7_000L)
                .isPositive();
        chain.effect(txSignature(sale), new RealChain.SwapEffect(40_000_000L, -offered, 5_000L, true));
        if (fails) {
            chain.fail(txSignature(sale), true);
        } else {
            chain.finalize(txSignature(sale));
        }
        confirmation.runOnce();
        return sale;
    }

    private static ErrorCode codeOf(Runnable call) {
        try {
            call.run();
        } catch (ApiException refused) {
            return refused.code();
        }
        throw new AssertionError("the call was not refused");
    }

    @Test
    void s19TwoPositionsAreSoldOneByOneAndThenEverythingIsWithdrawnDone() {
        Setup setup = realBot("AUTO_FULL");
        String second = token("0.0002", 6, 5);
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        position(wallet(setup), second, 7_000L, 2_000_000L);
        // 261121-paper-real-unico U2 (BR1.1): the sales come after the buys in the network order (their instant).
        clock.advance(java.time.Duration.ofSeconds(1));

        UUID liquidation = liquidationService.start(person(setup));
        sellOne(liquidation, 5_000L, false);
        sellOne(liquidation, 7_000L, false);
        liquidations.runOnce();

        assertThat(liquidationState(liquidation))
                .as(() -> jdbc.sql("select remaining::text from real_liquidation where liquidation_id = :id")
                        .param("id", liquidation)
                        .query(String.class)
                        .single())
                .isEqualTo("DONE");
        assertThat(jdbc.sql("select withdrawal_id from real_liquidation where liquidation_id = :id")
                        .param("id", liquidation)
                        .query(UUID.class)
                        .optional())
                .isPresent();
    }

    @Test
    void s20ASaleThatFailsEndsPartialWithThePositionAndTheReason() {
        Setup setup = realBot("AUTO_FULL");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);

        UUID liquidation = liquidationService.start(person(setup));
        sellOne(liquidation, 5_000L, true);
        liquidations.runOnce();

        assertThat(liquidationState(liquidation)).isEqualTo("PARTIAL");
        String remaining = jdbc.sql("select remaining::text from real_liquidation where liquidation_id = :id")
                .param("id", liquidation)
                .query(String.class)
                .single();
        assertThat(remaining)
                .contains(setup.mint())
                .contains("SLIPPAGE_EXCEEDED")
                .contains("5000");
    }

    @Test
    void aSecondRequestOrAnAmbiguousOrderIsRefusedWithConflict() {
        Setup setup = realBot("AUTO_FULL");
        liquidationService.start(person(setup));

        assertThat(codeOf(() -> liquidationService.start(person(setup))))
                .isEqualTo(ErrorCode.ORDER_ALREADY_IN_PROGRESS);

        Setup other = realBot("AUTO_FULL");
        UUID request = pendingBuy(other, 10_000_000L);
        consumer.runOnce();
        UUID order = orderOf(request);
        driver.runOnce();
        clock.advance(Duration.ofSeconds(60));
        confirmation.runOnce();
        assertThat(state(order)).isEqualTo("AMBIGUOUS");
        assertThat(codeOf(() -> liquidationService.start(person(other)))).isEqualTo(ErrorCode.ORDER_AMBIGUOUS);
    }

    @Test
    void theRequestCancelsTheOrdersNotSentAndDiscardsTheNextDecisions() {
        Setup setup = realBot("AUTO_FULL");
        UUID request = pendingBuy(setup, 10_000_000L);
        consumer.runOnce();
        UUID unsent = orderOf(request);

        liquidationService.start(person(setup));

        assertThat(state(unsent)).isEqualTo("CANCELLED");
        assertThat(failureCode(unsent)).isEqualTo("LIQUIDATION");
        assertThat(transactions
                        .execute(status ->
                                decisionStep.submit(com.memetrade.bots.application.RealDecisionStep.Candidate.buy(
                                        setup.tenantId(),
                                        setup.userId(),
                                        setup.botId(),
                                        UUID.randomUUID(),
                                        setup.mint(),
                                        10_000_000L,
                                        "x")))
                        .result())
                .isEqualTo("LIQUIDATION_ACTIVE");
    }

    @Test
    void s26TheLiquidationWaitsForABuyAlreadySentAndThenSellsItsPositionToo() {
        Setup setup = realBot("AUTO_FULL");
        UUID request = pendingBuy(setup, 10_000_000L);
        consumer.runOnce();
        UUID buy = orderOf(request);
        driver.runOnce();
        assertThat(state(buy)).isEqualTo("SENT");

        UUID liquidation = liquidationService.start(person(setup));
        liquidations.runOnce();
        assertThat(sales(liquidation)).as("the buy in flight is waited for").isEmpty();
        chain.effect(txSignature(buy), new RealChain.SwapEffect(-10_008_457L, 3_000L, 8_457L, true));
        chain.finalize(txSignature(buy));
        confirmation.runOnce();
        // 261121-paper-real-unico U2 (BR1.1): without a recorded slot the rows follow their instant; on the fixed clock
        // of the test the sale is written a second after the buy, as on the network.
        clock.advance(java.time.Duration.ofSeconds(1));
        sellOne(liquidation, 3_000L, false);
        liquidations.runOnce();

        assertThat(liquidationState(liquidation)).isEqualTo("DONE");
    }

    @Test
    void withoutAPositionTheLiquidationGoesStraightToTheWithdrawal() {
        Setup setup = realBot("AUTO_FULL");

        UUID liquidation = liquidationService.start(person(setup));
        liquidations.runOnce();

        assertThat(sales(liquidation)).isEmpty();
        assertThat(liquidationState(liquidation)).isEqualTo("DONE");
        assertThat(SyntheticIds.mint()).isNotBlank();
    }
}
