package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.api.RealChain;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.RealBotPnlQuery;
import com.memetrade.pnl.api.RealModePnl;
import com.memetrade.riskcontrol.api.GateDecision;
import com.memetrade.riskcontrol.api.OrderIntent;
import com.memetrade.riskcontrol.api.RealRiskGate;
import com.memetrade.testsupport.AbstractPaperRealIT;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 261121-paper-real-unico U2, Etapa 20 (AC3.11.3, BR10.2, NFR1.10, ADR-004): the read of the P&L REAL takes no lock.
 * Inside its transaction it holds only {@code AccessShareLock} on the tables it reads: no {@code FOR UPDATE} (no
 * {@code RowShareLock} and no transaction id), no write lock and no advisory lock. With C4 read in a loop in parallel,
 * the evaluations of the REAL barrier never fail and each one, wait for the wallet lock included, stays at or below
 * 1,000 ms (U3-NFR3.3). The {@code RealCycle} runs in the worker process and is not in this API context; it takes the
 * same wallet lock as the barrier, so the barrier stands for it here.
 */
@Tag("u2-real-bot-pnl")
class RealPnlReadOnlyIT extends AbstractPaperRealIT {

    private static final long LOCK_WAIT_CEILING_NANOS = Duration.ofMillis(1_000).toNanos();

    @Autowired
    private RealBotPnlQuery query;

    @Autowired
    private RealRiskGate gate;

    /** A confirmed buy of the bot of the setup, with its entries in the ledger. */
    private void confirmedBuy(Setup setup) {
        UUID request = pendingBuy(setup, 50_000_000L);
        consumer.runOnce();
        UUID order = orderOf(request);
        driver.runOnce();
        chain.effect(txSignature(order), new RealChain.SwapEffect(-52_047_737L, 1_000_000L, 8_457L, true));
        chain.finalize(txSignature(order));
        confirmation.runOnce();
    }

    @Test
    void theReadHoldsNoRowLockNoWriteLockAndNoAdvisoryLock() {
        Setup setup = realBot("AUTO_FULL");
        confirmedBuy(setup);

        List<String> locks = transactions.execute(status -> {
            RealModePnl pnl = query.modePnl(setup.tenantId(), setup.userId(), PnlWindow.all());
            assertThat(pnl.lines()).isNotEmpty();
            return jdbc.sql("""
                            select locktype || ':' || mode
                            from pg_locks
                            where pid = pg_backend_pid()
                              and (locktype in ('transactionid', 'advisory', 'tuple')
                                   or (locktype = 'relation' and mode <> 'AccessShareLock'))""").query(String.class).list();
        });

        assertThat(locks)
                .as("locks other than AccessShareLock held by the read")
                .isEmpty();
    }

    @Test
    void theBarrierNeverWaitsMoreThanOneSecondWhileC4IsReadInALoop() throws Exception {
        Setup setup = realBot("AUTO_FULL");
        confirmedBuy(setup);
        AtomicBoolean reading = new AtomicBoolean(true);
        AtomicInteger reads = new AtomicInteger();
        ExecutorService readers = Executors.newFixedThreadPool(2);
        List<CompletableFuture<Void>> loops = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            loops.add(CompletableFuture.runAsync(
                    () -> {
                        while (reading.get()) {
                            query.modePnl(setup.tenantId(), setup.userId(), PnlWindow.all());
                            reads.incrementAndGet();
                        }
                    },
                    readers));
        }
        List<Long> evaluations = new ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                long sale = timed(() -> gate.evaluate(OrderIntent.botSell(
                        setup.tenantId(), setup.userId(), setup.botId(), UUID.randomUUID(), setup.mint(), 1_000)));
                long buy = timed(() -> gate.evaluate(OrderIntent.botBuy(
                        setup.tenantId(), setup.userId(), setup.botId(), UUID.randomUUID(), setup.mint(), 1_000_000L)));
                evaluations.add(sale);
                evaluations.add(buy);
            }
        } finally {
            reading.set(false);
            readers.shutdown();
        }
        assertThat(readers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        for (CompletableFuture<Void> loop : loops) {
            loop.get(); // a failed read of C4 fails the test here
        }

        assertThat(reads.get()).as("reads of C4 during the evaluations").isPositive();
        assertThat(evaluations).hasSize(40).allSatisfy(nanos -> assertThat(nanos)
                .as("an evaluation with its wait for the wallet lock")
                .isLessThanOrEqualTo(LOCK_WAIT_CEILING_NANOS));
    }

    /** Runs one evaluation in its own transaction (a failure fails the test) and returns its time in nanoseconds. */
    private long timed(java.util.function.Supplier<GateDecision> evaluation) {
        long start = System.nanoTime();
        GateDecision decision = transactions.execute(status -> evaluation.get());
        long elapsed = System.nanoTime() - start;
        assertThat(decision).isNotNull();
        return elapsed;
    }
}
