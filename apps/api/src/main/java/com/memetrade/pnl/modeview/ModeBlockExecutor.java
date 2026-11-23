package com.memetrade.pnl.modeview;

import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.pnl.infra.PnlByModeProperties;
import com.memetrade.shared.config.ProcessRole;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U1 (NFR1.9, NFR1.11, NFR3.12, NFR4.4; performance-design.md "Execução de cada bloco"): the
 * bounded pool {@code pnl-mode-block} of the blocks of the P&L by mode (a bulkhead: at most {@code pool-size}
 * connections of Hikari at once). Not an {@code Executor} bean, so it never replaces the task executor of Spring Boot;
 * shut down with the context. Each task:
 *
 * <ol>
 *   <li>applies the MDC captured on the request thread (the correlation id), or clears the MDC when it was null, and
 *       always clears it at the end;
 *   <li>checks the deadline before opening its transaction (it may have waited in the queue) and gives up without
 *       reading;
 *   <li>opens its own transaction and checks the deadline AGAIN right after it opened (getting a connection may take
 *       longer than the deadline), then sets the statement timeout to what is left.
 * </ol>
 *
 * A failed block whose failure is an {@link AfterBlockRelease} gets its deferred work run on its thread right after
 * the transaction ended and the future was completed (review R-01: the audit of a REAL mismatch never runs while the
 * block holds a connection of the main pool, and never delays the answer).
 *
 * <p>A full queue refuses the task at once, on the request thread: {@link #submit} catches that and answers a future
 * already failed with {@link Rejected}, so a full pool is an UNAVAILABLE block and never a 500.
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class ModeBlockExecutor implements DisposableBean {

    private final ThreadPoolExecutor pool;
    private final BlockReads transactions;

    @Autowired
    public ModeBlockExecutor(PnlByModeProperties properties, BlockReads transactions) {
        this(properties.poolSize(), properties.queueCapacity(), transactions);
    }

    public ModeBlockExecutor(int poolSize, int queueCapacity, BlockReads transactions) {
        BlockingQueue<Runnable> queue =
                queueCapacity == 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(queueCapacity);
        this.pool = new ThreadPoolExecutor(
                poolSize, poolSize, 0L, TimeUnit.MILLISECONDS, queue, daemons(), new ThreadPoolExecutor.AbortPolicy());
        this.transactions = transactions;
    }

    /** The block was refused by a full pool and queue. */
    public static final class Rejected extends RuntimeException {
        Rejected(RejectedExecutionException cause) {
            super("The pool of the blocks is full", cause);
        }
    }

    /** The deadline of the block passed before it could read. */
    public static final class DeadlineExpired extends RuntimeException {
        DeadlineExpired() {
            super("The deadline of the block passed before it read");
        }
    }

    /**
     * @param deadline the instant of {@link System#nanoTime()} the block must be done by, counted from its submission
     */
    public <T> CompletableFuture<T> submit(PnlMarket market, long deadline, Callable<T> work) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            pool.execute(() -> run(market, deadline, work, mdc, future));
        } catch (RejectedExecutionException full) {
            future.completeExceptionally(new Rejected(full));
        }
        return future;
    }

    private <T> void run(
            PnlMarket market, long deadline, Callable<T> work, Map<String, String> mdc, CompletableFuture<T> future) {
        if (mdc == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(mdc);
        }
        try {
            requireTime(deadline);
            T value = transactions.inTransaction(market, () -> {
                transactions.statementTimeout(requireTime(deadline));
                return work.call();
            });
            future.complete(value);
        } catch (Exception failure) {
            future.completeExceptionally(failure);
            // The transaction of the block has ended (its connection is back in the main pool) and the answer is
            // given: only now the deferred work of the failure runs, still with the MDC of the request.
            afterTransaction(failure);
        } finally {
            MDC.clear();
        }
    }

    /** The first {@link AfterBlockRelease} of the chain of causes of a failed block, run once. */
    static void afterTransaction(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AfterBlockRelease deferred) {
                deferred.afterTransaction();
                return;
            }
            if (cause.getCause() == cause) {
                return;
            }
        }
    }

    /** What is left of the deadline; none left gives up. */
    private static Duration requireTime(long deadline) {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            throw new DeadlineExpired();
        }
        return Duration.ofNanos(left);
    }

    /** The tasks running now (for the tests of saturation). */
    public int activeCount() {
        return pool.getActiveCount();
    }

    @Override
    public void destroy() {
        pool.shutdownNow();
    }

    private static ThreadFactory daemons() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "pnl-mode-block-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
