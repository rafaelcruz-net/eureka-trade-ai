package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U4, Etapa 5 (written before the code; BR3.2; security-design.md "Barreira 2"): every cycle
 * that ends REAL_RECORDED with a SELL leaves {@code bot.change_seq} above the mark read under the lock, in the same
 * transaction as the decision, so a later cycle that read the old mark can never sell again.
 */
@Tag("u4-own-quantity-cycle")
class RenewMarkOnSellIT extends AbstractOwnQuantityIT {

    @Test
    void aRecordedSellRenewsTheMarkOfTheBotInTheTransactionOfTheDecision() {
        Pair pair = pair();
        OwnQuantityCycle cycle = cycle(true).sellsAll(pair.xyz());
        AtomicLong underLock = new AtomicLong();
        // Read after the snapshot and before the transaction: nothing else touches the bot, so it is the M1 of the
        // lock.
        cycle.holdings.after = () -> underLock.set(mark(pair));

        CycleResult result = cycle.run(pair.refA(), slot());

        assertThat(outcome(result)).isEqualTo("REAL_RECORDED");
        assertThat(sellDecisions(pair.a())).containsExactly(A_UNITS);
        assertThat(mark(pair)).isGreaterThan(underLock.get());
        assertThat(jdbc.sql("""
                        select count(*) from real_decision_request r join bot_cycle c on c.decision_id = r.decision_id
                        where c.cycle_id = :c and r.side = 'SELL'""")
                        .param("c", result.cycleId().orElseThrow())
                        .query(Long.class)
                        .single())
                .as("the decision and the cycle committed together")
                .isEqualTo(1L);
    }

    private long mark(Pair pair) {
        return jdbc.sql("select change_seq from bot where bot_id = :b")
                .param("b", pair.a())
                .query(Long.class)
                .single();
    }
}
