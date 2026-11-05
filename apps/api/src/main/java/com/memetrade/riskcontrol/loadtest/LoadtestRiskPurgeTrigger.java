package com.memetrade.riskcontrol.loadtest;

import com.memetrade.riskcontrol.infra.RiskEvaluationPurgeJob;
import com.memetrade.riskcontrol.infra.RiskEvaluationPurgeJob.Round;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * EXISTS ONLY WITH THE "loadtest" PROFILE: fires the purge of U6 once, at {@code purge-at} after the cycles of the
 * scenario {@code u6} started, without waiting for the cron and through the same ShedLock lock, so the second window
 * of the scenario measures the evaluation with the purge running at the same time (NFR2.5).
 */
@Component
@Profile("loadtest")
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class LoadtestRiskPurgeTrigger {

    private static final Logger log = LoggerFactory.getLogger(LoadtestRiskPurgeTrigger.class);

    private final RiskEvaluationPurgeJob job;
    private volatile Round last;

    public LoadtestRiskPurgeTrigger(RiskEvaluationPurgeJob job) {
        this.job = job;
    }

    /** Runs one round now; the batches are timed in the log of the round ("Risk purge batch ... durationMs"). */
    public Round fire() {
        log.warn("LOADTEST PROFILE ACTIVE: firing the risk purge now");
        Round round = job.runOnce();
        last = round;
        log.info(
                "Load risk purge done: ran={}, result={}, deleted={}, batches={}",
                round.ran(),
                round.result(),
                round.deleted(),
                round.batches());
        return round;
    }

    public Optional<Round> last() {
        return Optional.ofNullable(last);
    }
}
