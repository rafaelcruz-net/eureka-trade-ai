package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.memetrade.testsupport.AbstractPaperRealIT;
import com.memetrade.testsupport.SyntheticIds;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U3, Etapa 13 (NFR1.10; NFR1 of the intent): the reads of C5 and C6 never wait for the lock of
 * the execution of orders, and never make it wait. The cycle of a bot holds the row of its bot wallet ({@code FOR
 * UPDATE}, the same lock the barrier takes) for up to 2 s; this test holds that lock in its own transaction while 4
 * threads read C5 and C6 in a loop: every read answers 200 while the lock is held, and the "cycle" releases its lock on
 * time. Deviation recorded in the code summary: the cycle of a bot cannot run in the API context of the IT (as the U2
 * found in {@code RealPnlReadOnlyIT}), so the lock of the wallet plays its part.
 */
@Tag("u3-pnl-views")
class PnlReadsDoNotBlockCycleIT extends AbstractPaperRealIT {

    private static final long SOL = 1_000_000_000L;

    @Test
    void readsInALoopNeverWaitForTheLockOfTheCycle() throws Exception {
        Person person = signIn(true, true);
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        UUID paper = rows.paperBot(person.tenantId(), person.userId(), "S", PnlViewsRows.STRATEGY);
        UUID real = rows.realBot(person.tenantId(), person.userId(), "R", PnlViewsRows.STRATEGY);
        UUID wallet = activeWallet(person, 5 * SOL);
        new RealPnlSeed(
                        jdbc,
                        person.tenantId(),
                        person.userId(),
                        wallet,
                        clock.instant().minus(Duration.ofDays(1)),
                        7_000_000)
                .buy(real, SyntheticIds.mint(), 10, 10_000_000L, 1_000L);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch readsDone = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            Future<Long> cycle = pool.submit(() -> transactions.execute(status -> {
                jdbc.sql("select bot_wallet_id from bot_wallet_account where bot_wallet_id = :id for update")
                        .param("id", wallet)
                        .query(UUID.class)
                        .single();
                locked.countDown();
                long started = System.nanoTime();
                try {
                    readsDone.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return System.nanoTime() - started;
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            List<Future<Integer>> readers = new ArrayList<>();
            for (int thread = 0; thread < 4; thread++) {
                readers.add(pool.submit(() -> {
                    int ok = 0;
                    for (int i = 0; i < 5; i++) {
                        ok += read(person, get("/api/v1/bots/" + real + "/pnl/lines"))
                                                .getStatus()
                                        == 200
                                ? 1
                                : 0;
                        ok += read(person, get("/api/v1/pnl/compare?paperBotId=" + paper + "&realBotId=" + real))
                                                .getStatus()
                                        == 200
                                ? 1
                                : 0;
                    }
                    return ok;
                }));
            }
            for (Future<Integer> reader : readers) {
                assertThat(reader.get(15, TimeUnit.SECONDS)).isEqualTo(10);
            }
            // Every read answered while the lock of the cycle was still held: they never waited for it.
            assertThat(cycle.isDone()).isFalse();
            readsDone.countDown();
            assertThat(Duration.ofNanos(cycle.get(5, TimeUnit.SECONDS))).isLessThan(Duration.ofSeconds(20));
        } finally {
            readsDone.countDown();
            pool.shutdownNow();
        }
    }
}
