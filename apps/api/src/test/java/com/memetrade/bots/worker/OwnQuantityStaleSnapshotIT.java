package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U4, Etapa 5, T10 (written before the code; C0, C1; BR3.1, BR3.2): two cycles of A (slots t
 * and t+5 min) run together and both read the same mark M0 and 100 XYZ available. The first locks the bot, records the
 * SELL and renews the mark; the second locks after it, reads M1 different from M0 and ends REAL_OWN_QUANTITY_UNAVAILABLE
 * with STALE_SNAPSHOT and no decision: the total sold never passes the own quantity. A decision committed between M0
 * and the lock only ever produces STALE_SNAPSHOT. The barriers are latches, never time.
 */
@Tag("u4-own-quantity-cycle")
class OwnQuantityStaleSnapshotIT extends AbstractOwnQuantityIT {

    @Test
    void c1TwoCyclesOfTheSameBotNeverSellMoreThanItsOwnQuantity() throws Exception {
        Pair pair = pair();
        Instant t = slot();
        Instant later = t.plus(Duration.ofMinutes(5));
        OwnQuantityCycle cycle = cycle(true).sellsAll(pair.xyz());
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        cycle.holdings.after = () -> {
            if (calls.incrementAndGet() == 1) {
                read.countDown();
                await(go);
            }
        };

        // The late cycle (t+5) reads M0 and its snapshot first and is held before its transaction.
        CompletableFuture<CycleResult> held = CompletableFuture.supplyAsync(() -> cycle.run(pair.refA(), later));
        assertThat(read.await(20, TimeUnit.SECONDS)).isTrue();
        // The cycle of t runs whole: it reads the same M0, records the SELL and renews the mark.
        CycleResult first = cycle.run(pair.refA(), t);
        go.countDown();
        CycleResult second = held.get(30, TimeUnit.SECONDS);

        assertThat(outcome(first)).isEqualTo("REAL_RECORDED");
        assertThat(outcome(second)).isEqualTo("REAL_OWN_QUANTITY_UNAVAILABLE");
        assertThat(context(second)).contains("\"STALE_SNAPSHOT\"");
        assertThat(sellDecisions(pair.a())).containsExactly(A_UNITS);
        assertThat(cycle.meters
                        .get("real_cycle_sell_capped")
                        .tag("outcome", "STALE_SNAPSHOT")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(cycle.meters.get("real_own_holdings_unavailable").counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    void c0ADecisionCommittedBetweenTheMarkAndTheLockOnlyGivesStaleSnapshot() {
        Pair pair = pair();
        OwnQuantityCycle cycle = cycle(true).sellsAll(pair.xyz());
        // After M0 and the snapshot, another transaction records a decision of A and renews its mark.
        cycle.holdings.after = () -> jdbc.sql("update bot set change_seq = nextval('bot_change_seq') where bot_id = :b")
                .param("b", pair.a())
                .update();

        CycleResult result = cycle.run(pair.refA(), slot());

        assertThat(outcome(result)).isEqualTo("REAL_OWN_QUANTITY_UNAVAILABLE");
        assertThat(sellDecisions(pair.a())).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the barrier of the test was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
