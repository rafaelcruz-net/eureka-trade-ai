package com.memetrade.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Runs tasks at the same time behind a start barrier and collects each result or failure (no sleeps). */
public final class Concurrently {

    private Concurrently() {}

    /** The result of one task: its value, or the failure it threw. */
    public record Outcome<T>(T value, Throwable failure) {
        public boolean failed() {
            return failure != null;
        }
    }

    @SafeVarargs
    public static <T> List<Outcome<T>> run(Callable<T>... tasks) {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CyclicBarrier start = new CyclicBarrier(tasks.length);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    outcomes.add(new Outcome<>(future.get(60, TimeUnit.SECONDS), null));
                } catch (java.util.concurrent.ExecutionException failed) {
                    outcomes.add(new Outcome<>(null, failed.getCause()));
                } catch (java.util.concurrent.TimeoutException stuck) {
                    outcomes.add(new Outcome<>(null, stuck));
                }
            }
            return outcomes;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the concurrent tasks", interrupted);
        } finally {
            pool.shutdownNow();
        }
    }
}
