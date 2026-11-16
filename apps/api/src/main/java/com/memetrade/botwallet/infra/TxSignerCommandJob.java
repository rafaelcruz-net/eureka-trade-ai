package com.memetrade.botwallet.infra;

import com.memetrade.botwallet.application.TxSignerCommandService;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.web.CorrelationId;
import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 261113-trading-real-bot U5 (BR4.1, BR6.2, U5-NFR4.2, U5-NFR5.11): every minute, once across instances, the commands to
 * the TxSigner not yet confirmed are sent again with their own commandId; the heartbeat {@code job="txsigner-resend"}
 * counts every run.
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
@ConditionalOnProperty(name = "memetrade.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class TxSignerCommandJob {

    private final TxSignerCommandService commands;
    private final RealOpsMetrics metrics;

    public TxSignerCommandJob(TxSignerCommandService commands, RealOpsMetrics metrics) {
        this.commands = commands;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${memetrade.real.ops.txsigner-resend-interval}")
    @SchedulerLock(name = "realOpsTxSignerResend", lockAtMostFor = "55s", lockAtLeastFor = "5s")
    public void run() {
        CorrelationId.runWithNew(() -> {
            long started = System.nanoTime();
            commands.resendPending();
            metrics.jobRan("txsigner-resend", Duration.ofNanos(System.nanoTime() - started));
        });
    }
}
