package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.realexecution.OpenSellsFixture;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U4, Etapa 4, T4 (written before the code; E5, C3, AC6.1.1; BR1.2, BR1.3): the open sales of
 * the bot are discounted before the exit is evaluated: a PENDING SELL decision (the deferred one too), an order in
 * progress and an AMBIGUOUS order. With 60 XYZ of A still in the queue the exit is evaluated on 40 XYZ and the second
 * decision carries 40,000,000: the two add up to the own quantity of A.
 */
@Tag("u4-own-quantity-cycle")
class OwnQuantityOpenSellsIT extends AbstractOwnQuantityIT {

    @Test
    void e5APendingSellOfABotIsDiscountedAndTheSecondDecisionCompletesItsOwnQuantity() {
        Pair pair = pair();
        new OpenSellsFixture(jdbc).pendingSell(pair.tenantId(), pair.userId(), pair.a(), pair.xyz(), 60_000_000L);
        OwnQuantityCycle cycle = cycle(true).sellsAll(pair.xyz());

        CycleResult result = cycle.run(pair.refA(), slot());

        assertThat(cycle.lastRequest().positions().getFirst().quantity()).isEqualByComparingTo("40");
        assertThat(outcome(result)).isEqualTo("REAL_RECORDED");
        assertThat(sellDecisions(pair.a())).containsExactlyInAnyOrder(60_000_000L, 40_000_000L);
        assertThat(sellDecisions(pair.a()).stream().mapToLong(Long::longValue).sum())
                .isEqualTo(A_UNITS);
    }

    @Test
    void c3ADeferredPendingSellIsDiscountedToo() {
        Pair pair = pair();
        new OpenSellsFixture(jdbc)
                .decision(
                        pair.tenantId(),
                        pair.userId(),
                        pair.a(),
                        "SELL",
                        pair.xyz(),
                        60_000_000L,
                        "PENDING",
                        Instant.now().plus(Duration.ofMinutes(30)));
        OwnQuantityCycle cycle = cycle(true).sellsAll(pair.xyz());

        cycle.run(pair.refA(), slot());

        assertThat(cycle.lastRequest().positions().getFirst().quantity()).isEqualByComparingTo("40");
        assertThat(sellDecisions(pair.a())).containsExactlyInAnyOrder(60_000_000L, 40_000_000L);
    }

    @Test
    void anOrderInProgressAndAnAmbiguousOrderAreDiscounted() {
        Pair sent = pair();
        new OpenSellsFixture(jdbc)
                .order(sent.tenantId(), sent.userId(), sent.a(), "SELL", sent.xyz(), 30_000_000L, "SENT");
        OwnQuantityCycle cycle = cycle(true).sellsAll(sent.xyz());

        CycleResult result = cycle.run(sent.refA(), slot());

        assertThat(cycle.lastRequest().positions().getFirst().quantity()).isEqualByComparingTo("70");
        // The step of today still refuses a second order while one is in progress.
        assertThat(outcome(result)).isEqualTo("REAL_ORDER_IN_PROGRESS");

        Pair ambiguous = pair();
        new OpenSellsFixture(jdbc)
                .order(
                        ambiguous.tenantId(),
                        ambiguous.userId(),
                        ambiguous.a(),
                        "SELL",
                        ambiguous.xyz(),
                        99_000_000L,
                        "AMBIGUOUS");
        cycle.sellsAll(ambiguous.xyz()).run(ambiguous.refA(), slot());

        assertThat(cycle.lastRequest().positions().getFirst().quantity()).isEqualByComparingTo("1");
    }
}
