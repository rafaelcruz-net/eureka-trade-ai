package com.memetrade.botwallet.infra;

import com.memetrade.botwallet.application.ReconciliationService;
import com.memetrade.botwallet.application.ReconciliationService.DueWallet;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.web.CorrelationId;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 261113-trading-real-bot U5 (BR2.1, U5-NFR3.2, U5-NFR4.2): the reconciliation job of the worker. Every
 * {@code reconciliation-tick} (15 s) it reconciles the wallets that are due (15 minutes, after a confirmed order or
 * withdrawal, after a compensation, after a restore) with an executor of at most 4 wallets at once, which uses the 4
 * reserve connections of the pool. Once across instances (ShedLock {@code realOpsReconciliation}). A failure of one
 * wallet is logged and never stops the others; the run that ends counts the heartbeat {@code job="reconciliation"}.
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
@ConditionalOnProperty(name = "memetrade.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class RealReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(RealReconciliationJob.class);

    private final ReconciliationService reconciliation;
    private final RealOpsMetrics metrics;
    private final ExecutorService executor;

    public RealReconciliationJob(ReconciliationService reconciliation, RealOpsMetrics metrics, RealOpsProperties ops) {
        this.reconciliation = reconciliation;
        this.metrics = metrics;
        this.executor = Executors.newFixedThreadPool(Math.max(1, ops.reconciliationThreads()), runnable -> {
            Thread thread = new Thread(runnable, "real-reconciliation");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Scheduled(fixedDelayString = "${memetrade.real.ops.reconciliation-tick}")
    @SchedulerLock(name = "realOpsReconciliation", lockAtMostFor = "5m", lockAtLeastFor = "5s")
    public void run() {
        CorrelationId.runWithNew(() -> {
            long started = System.nanoTime();
            runOnce();
            metrics.jobRan("reconciliation", Duration.ofNanos(System.nanoTime() - started));
        });
    }

    /** One round: every due wallet, at most 4 at once; returns how many were reconciled. */
    int runOnce() {
        List<Future<?>> rounds = new ArrayList<>();
        for (DueWallet due : reconciliation.dueWallets()) {
            rounds.add(executor.submit(() -> CorrelationId.runWithNew(() -> {
                try {
                    reconciliation.reconcile(due.botWalletId(), due.trigger());
                } catch (RuntimeException failure) {
                    log.error(
                            "REAL reconciliation failed: botWalletId={}, cause={}",
                            due.botWalletId(),
                            failure.getClass().getSimpleName());
                }
            })));
        }
        int done = 0;
        for (Future<?> round : rounds) {
            try {
                round.get();
                done++;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("The reconciliation round was interrupted", interrupted);
            } catch (java.util.concurrent.ExecutionException failed) {
                log.error(
                        "REAL reconciliation round failed: {}",
                        failed.getCause().getClass().getSimpleName());
            }
        }
        return done;
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
