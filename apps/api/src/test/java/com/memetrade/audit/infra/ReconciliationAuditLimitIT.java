package com.memetrade.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.api.RateLimitedAuditPublisher;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.RealPnlUnavailable;
import com.memetrade.pnl.application.PnlRealMetrics;
import com.memetrade.pnl.application.ReconciliationAuditGate;
import com.memetrade.pnl.application.ReconciliationMismatch;
import com.memetrade.pnl.infra.PnlMismatchAuditProperties;
import com.memetrade.pnl.real.ReconciliationResult;
import com.memetrade.testsupport.AbstractPaperRealIT;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 261121-paper-real-unico U2, Etapa 21 (written after the layer; NFR3.15, NFR3.16, R-07, R-09, R-10): the event of a
 * REAL P&L mismatch is written at most once per hour per fingerprint, from what the trail already holds, on the pool
 * {@code pnl-mismatch-audit}; the answer of the block never changes because of it.
 *
 * <p>Decisions of the founder of 2026-11-23 (review R-01 and R-05): the fingerprint is the person and the kinds of the
 * failed checks, without values, so a difference that changes at every read is still one event per hour; and the write
 * runs after the read transaction of the block ended ({@link ReconciliationMismatch#afterTransaction()}, which the
 * pool of the blocks calls; here the test calls it like the pool does).
 */
@Tag("u2-real-bot-pnl")
class ReconciliationAuditLimitIT extends AbstractPaperRealIT {

    @Autowired
    private ReconciliationAuditGate gate;

    @Autowired
    private MismatchAuditDataSource pool;

    @Autowired
    private MismatchAuditWriter writer;

    @Autowired
    private PnlRealMetrics metrics;

    @Autowired
    private DataSource main;

    private static ReconciliationResult mismatch(long difference, ReconciliationResult.Check check) {
        return new ReconciliationResult(
                PnlWindow.all(),
                ReconciliationResult.Status.MISMATCH,
                difference,
                0,
                Optional.of(0L),
                List.of(check),
                Map.of(check.name(), difference));
    }

    private static ReconciliationResult mismatch(long realized) {
        return mismatch(realized, ReconciliationResult.Check.REALIZED);
    }

    /** One read that did not reconcile, as the block does it: the answer, then the audit after its transaction. */
    private static RealPnlUnavailable read(
            ReconciliationAuditGate gate, UUID tenant, UUID user, ReconciliationResult result) {
        ReconciliationMismatch answer = gate.onMismatch(tenant, user, result);
        answer.afterTransaction();
        return answer;
    }

    private long events(UUID userId) {
        return jdbc.sql("""
                        select (select count(*) from audit_outbox
                                where payload ->> 'action' = 'REAL_PNL_RECONCILIATION_MISMATCH'
                                  and payload ->> 'actor' = :actor)
                             + (select count(*) from audit_event
                                where action = 'REAL_PNL_RECONCILIATION_MISMATCH' and actor = :actor)""").param("actor", userId.toString()).query(Long.class).single();
    }

    /**
     * The events of the person once the writes in flight are done: a write may still finish on the executor after the
     * block answered (the first connection of the pool is opened lazily); waits up to 3 s for {@code expected}.
     */
    private long eventsSettled(UUID userId, long expected) throws InterruptedException {
        long events = events(userId);
        for (int attempt = 0; attempt < 30 && events != expected; attempt++) {
            Thread.sleep(100);
            events = events(userId);
        }
        return events;
    }

    private double counter(String name, String... tags) {
        return meters.counter(name, tags).count();
    }

    @Test
    void tenReadsWithChangingValuesWriteOneEventAndCountTen() throws InterruptedException {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        double before = counter(PnlRealMetrics.MISMATCHES, "check", "REALIZED");

        for (int i = 0; i < 10; i++) {
            RealPnlUnavailable answer = read(gate, tenant, user, mismatch(-1 - i));
            assertThat(answer.causeLabel()).isEqualTo(RealPnlUnavailable.RECONCILIATION_MISMATCH);
        }

        assertThat(eventsSettled(user, 1))
                .as("a difference that changes at every read is the same fingerprint")
                .isEqualTo(1);
        assertThat(counter(PnlRealMetrics.MISMATCHES, "check", "REALIZED")).isEqualTo(before + 10);
        read(gate, tenant, user, mismatch(-1, ReconciliationResult.Check.FEES));
        assertThat(eventsSettled(user, 2))
                .as("another kind of check is another fingerprint")
                .isEqualTo(2);
    }

    @Test
    void twoThreadsAtTheSameTimeWriteOneEventOnTheSameConnectionForTheLockAndTheInsert() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        List<Long> pids = new CopyOnWriteArrayList<>();
        writer.observeBackendPids(pids::add);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<RealPnlUnavailable>> answers = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                answers.add(threads.submit(() -> {
                    start.await();
                    return read(gate, tenant, user, mismatch(-7));
                }));
            }
            start.countDown();
            for (Future<RealPnlUnavailable> answer : answers) {
                assertThat(answer.get(5, TimeUnit.SECONDS).causeLabel())
                        .isEqualTo(RealPnlUnavailable.RECONCILIATION_MISMATCH);
            }
        } finally {
            threads.shutdownNow();
            writer.observeBackendPids(pid -> {});
        }

        assertThat(eventsSettled(user, 1)).isEqualTo(1);
        // Each write reports the pid after its lock and, when it inserts, after its insert: the insert of the one
        // event ran on the connection that held the lock.
        assertThat(pids).hasSizeBetween(2, 3);
    }

    @Test
    void aFailingPublisherNeverChangesTheAnswerAndCountsAFailure() {
        RateLimitedAuditPublisher failing = (command, fingerprint, deadline) -> {
            throw new IllegalStateException("test: the trail is down");
        };
        ReconciliationAuditGate broken =
                new ReconciliationAuditGate(failing, clock, metrics, PnlMismatchAuditProperties.defaults());
        double failures = counter(PnlRealMetrics.AUDIT_FAILURES);
        try {
            for (int i = 0; i < 3; i++) {
                assertThat(read(broken, UUID.randomUUID(), UUID.randomUUID(), mismatch(-1))
                                .causeLabel())
                        .isEqualTo(RealPnlUnavailable.RECONCILIATION_MISMATCH);
            }
        } finally {
            broken.destroy();
        }
        assertThat(counter(PnlRealMetrics.AUDIT_FAILURES)).isEqualTo(failures + 3);
    }

    @Test
    void theAuditRunsOnceWhateverTheCallsAfterTheTransaction() throws InterruptedException {
        UUID user = UUID.randomUUID();
        double failures = counter(PnlRealMetrics.AUDIT_FAILURES);
        ReconciliationMismatch answer = gate.onMismatch(UUID.randomUUID(), user, mismatch(-5));

        answer.afterTransaction();
        answer.afterTransaction();

        assertThat(eventsSettled(user, 1)).isEqualTo(1);
        assertThat(counter(PnlRealMetrics.AUDIT_FAILURES)).isEqualTo(failures);
    }

    @Test
    void withItsOwnPoolHeldTheWriteGivesUpInTimeAndWritesNothing() throws Exception {
        UUID user = UUID.randomUUID();
        double failures = counter(PnlRealMetrics.AUDIT_FAILURES);
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < PnlMismatchAuditProperties.DEFAULT_POOL_SIZE; i++) {
                held.add(pool.dataSource().getConnection());
            }
            long started = System.nanoTime();

            RealPnlUnavailable answer = read(gate, UUID.randomUUID(), user, mismatch(-3));

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(400));
            assertThat(answer.causeLabel()).isEqualTo(RealPnlUnavailable.RECONCILIATION_MISMATCH);
            // Keep the pool held past the 250 ms wait of the write, so it gives up on its connection by itself.
            Thread.sleep(500);
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
        Thread.sleep(400);
        assertThat(events(user)).isZero();
        assertThat(counter(PnlRealMetrics.AUDIT_FAILURES)).isEqualTo(failures + 1);
    }

    @Test
    void withTheMainPoolHeldTheEventIsStillWrittenByItsOwnPool() throws Exception {
        UUID user = UUID.randomUUID();
        int size = main.unwrap(HikariDataSource.class).getMaximumPoolSize();
        List<Connection> held = new ArrayList<>();
        RealPnlUnavailable answer;
        try {
            for (int i = 0; i < size; i++) {
                held.add(main.getConnection());
            }
            answer = read(gate, UUID.randomUUID(), user, mismatch(-4));
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
        assertThat(answer.causeLabel()).isEqualTo(RealPnlUnavailable.RECONCILIATION_MISMATCH);
        assertThat(eventsSettled(user, 1)).isEqualTo(1);
    }
}
