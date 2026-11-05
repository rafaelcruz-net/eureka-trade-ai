package com.memetrade.bots.worker;

import com.memetrade.bots.infra.BotProperties;
import com.memetrade.bots.infra.JdbcCycleRepository;
import com.memetrade.decision.infra.JdbcDecisionRepository;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * BR9.1 (F11): every day, the HOLD and NO_TRADE decisions older than {@code decision-retention} (30 days) are deleted
 * with their cycles, then the old cycles without a decision (skipped or failed), in batches of
 * {@code decision-purge-batch-size} (1.000), each batch in its own short statement. BUY and SELL stay for the whole
 * beta. The current slot is never touched. Once across instances (ShedLock {@code bots-decision-purge}); a repeated round
 * deletes nothing more (NFR10.5). The log carries totals only.
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
public class DecisionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(DecisionPurgeJob.class);
    static final String LOCK_NAME = "bots-decision-purge";

    private final JdbcDecisionRepository decisions;
    private final JdbcCycleRepository cycles;
    private final LockingTaskExecutor shedLock;
    private final Clock clock;
    private final Duration retention;
    private final int batchSize;

    public DecisionPurgeJob(
            JdbcDecisionRepository decisions,
            JdbcCycleRepository cycles,
            LockingTaskExecutor shedLock,
            Clock clock,
            BotProperties properties) {
        this.decisions = decisions;
        this.cycles = cycles;
        this.shedLock = shedLock;
        this.clock = clock;
        this.retention = properties.decisionRetention();
        this.batchSize = properties.decisionPurgeBatchSize();
    }

    public record Round(boolean ran, long decisions, long cycles) {}

    public Round runOnce() {
        return CorrelationId.withNew(() -> {
            LockConfiguration lock =
                    new LockConfiguration(clock.instant(), LOCK_NAME, Duration.ofMinutes(45), Duration.ZERO);
            try {
                LockingTaskExecutor.TaskResult<Round> result = shedLock.executeWithLock(this::round, lock);
                return result.wasExecuted() ? result.getResult() : new Round(false, 0, 0);
            } catch (RuntimeException | Error unexpected) {
                throw unexpected;
            } catch (Throwable checked) {
                throw new IllegalStateException("The decision purge failed with a checked exception", checked);
            }
        });
    }

    private Round round() {
        Instant cutoff = clock.instant().minus(retention);
        long deletedDecisions = 0;
        long deletedCycles = 0;
        while (true) {
            JdbcDecisionRepository.Purged batch = decisions.purgeBatch(cutoff, batchSize);
            deletedDecisions += batch.decisions();
            deletedCycles += batch.cycles();
            if (batch.decisions() < batchSize) {
                break;
            }
        }
        while (true) {
            int batch = cycles.purgeWithoutDecision(cutoff, batchSize);
            deletedCycles += batch;
            if (batch < batchSize) {
                break;
            }
        }
        log.info("Decision purge: cutoff={}, decisions={}, cycles={}", cutoff, deletedDecisions, deletedCycles);
        return new Round(true, deletedDecisions, deletedCycles);
    }
}
