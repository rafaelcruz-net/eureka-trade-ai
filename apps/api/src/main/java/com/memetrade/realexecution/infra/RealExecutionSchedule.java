package com.memetrade.realexecution.infra;

import com.memetrade.shared.config.ProcessRole;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The seven jobs of the REAL execution, only in the worker (logical-components.md, U4-NFR4.7). The jobs of orders take
 * a lease per order with {@code SKIP LOCKED} (queue, driver, confirmation), so several instances share the work; the
 * sweeps run once across instances (ShedLock: ambiguous, signing deadline, health, liquidation). With the REAL off the
 * consumer discards the decisions, and confirmation, ambiguous and liquidation keep finishing what was already sent
 * (rollback of infrastructure-specification.md). Each job counts {@code real_execution_job_runs_total} when a run ends
 * without failure (U4-NFR5.14).
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
@ConditionalOnProperty(name = "memetrade.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class RealExecutionSchedule {

    private final DecisionConsumer consumer;
    private final OrderDriver driver;
    private final ConfirmationJob confirmation;
    private final AmbiguousJob ambiguous;
    private final SigningDeadlineJob deadlines;
    private final UpstreamHealthJob health;
    private final LiquidationJob liquidations;

    public RealExecutionSchedule(
            DecisionConsumer consumer,
            OrderDriver driver,
            ConfirmationJob confirmation,
            AmbiguousJob ambiguous,
            SigningDeadlineJob deadlines,
            UpstreamHealthJob health,
            LiquidationJob liquidations) {
        this.consumer = consumer;
        this.driver = driver;
        this.confirmation = confirmation;
        this.ambiguous = ambiguous;
        this.deadlines = deadlines;
        this.health = health;
        this.liquidations = liquidations;
    }

    @Scheduled(fixedDelayString = "${memetrade.real.execution.jobs.queue-interval}", initialDelayString = "5s")
    public void queue() {
        consumer.runOnce();
    }

    @Scheduled(fixedDelayString = "${memetrade.real.execution.jobs.driver-interval}", initialDelayString = "5s")
    public void driver() {
        driver.runOnce();
    }

    @Scheduled(fixedDelayString = "${memetrade.real.execution.jobs.confirmation-interval}", initialDelayString = "5s")
    public void confirmation() {
        confirmation.runOnce();
    }

    @Scheduled(fixedDelayString = "${memetrade.real.execution.jobs.ambiguous-interval}", initialDelayString = "10s")
    @SchedulerLock(name = "realAmbiguous", lockAtMostFor = "1m", lockAtLeastFor = "5s")
    public void ambiguous() {
        ambiguous.runOnce();
    }

    @Scheduled(
            fixedDelayString = "${memetrade.real.execution.jobs.signing-deadline-interval}",
            initialDelayString = "5s")
    @SchedulerLock(name = "realSigningDeadline", lockAtMostFor = "30s", lockAtLeastFor = "500ms")
    public void signingDeadline() {
        deadlines.runOnce();
    }

    @Scheduled(fixedDelayString = "${memetrade.real.execution.jobs.health-interval}", initialDelayString = "15s")
    @SchedulerLock(name = "realUpstreamHealth", lockAtMostFor = "1m", lockAtLeastFor = "10s")
    public void health() {
        health.runOnce();
    }

    @Scheduled(fixedDelayString = "${memetrade.real.execution.jobs.liquidation-interval}", initialDelayString = "5s")
    @SchedulerLock(name = "realLiquidation", lockAtMostFor = "1m", lockAtLeastFor = "500ms")
    public void liquidation() {
        liquidations.runOnce();
    }
}
