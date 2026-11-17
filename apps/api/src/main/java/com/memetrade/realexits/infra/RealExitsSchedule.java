package com.memetrade.realexits.infra;

import com.memetrade.shared.config.ProcessRole;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The three jobs of the REAL exits, only in the worker (logical-components.md, U7-NFR4.4): the read of the conditionals
 * every 30 s, the automatic cancellation every minute and the gauges every 30 s. Each runs once across instances
 * (ShedLock with its own name), resumes from what the database holds and counts {@code real_exits_job_runs_total{job}}
 * when a run ends well (the stopped-job alerts of U7-NFR5.10). The cancellation never depends on the exits flag nor on
 * the REAL switch (rollback of infrastructure-specification.md).
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
@ConditionalOnProperty(name = "memetrade.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class RealExitsSchedule {

    private final ConditionalReadJob reads;
    private final ConditionalCancelJob cancels;
    private final RealExitsGaugesJob gauges;

    public RealExitsSchedule(ConditionalReadJob reads, ConditionalCancelJob cancels, RealExitsGaugesJob gauges) {
        this.reads = reads;
        this.cancels = cancels;
        this.gauges = gauges;
    }

    @Scheduled(fixedDelayString = "${memetrade.real.exits.read-interval}", initialDelayString = "20s")
    @SchedulerLock(name = "realExitsRead", lockAtMostFor = "2m", lockAtLeastFor = "5s")
    public void read() {
        reads.runOnce();
    }

    @Scheduled(fixedDelayString = "${memetrade.real.exits.cancel-interval}", initialDelayString = "20s")
    @SchedulerLock(name = "realExitsCancel", lockAtMostFor = "3m", lockAtLeastFor = "5s")
    public void cancel() {
        cancels.runOnce();
    }

    @Scheduled(fixedDelayString = "${memetrade.real.exits.gauges-interval}", initialDelayString = "20s")
    @SchedulerLock(name = "realExitsGauges", lockAtMostFor = "1m", lockAtLeastFor = "5s")
    public void gauges() {
        gauges.runOnce();
    }
}
