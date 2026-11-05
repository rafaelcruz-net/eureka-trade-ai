package com.memetrade.bots.worker;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.bots.api.PauseReason;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.BotStateMachine;
import com.memetrade.bots.domain.BotStateMachine.MidnightChange;
import com.memetrade.bots.infra.BotAudit;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.bots.infra.LockOrder;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BR2.6 (F6): at 00:00:00 UTC every day, only the DAILY_LOSS reason leaves. Each bot in its own transaction, in the
 * order of BR2.11 (the owner row, then the bot), counting the active bots under the owner lock: without any other
 * reason the bot returns to ACTIVE when the person has fewer than 3 active bots, otherwise it waits with
 * WAITING_ACTIVE_SLOT. Once across instances (ShedLock {@code bots-midnight}); repeatable without double effect, since
 * a bot without DAILY_LOSS does not change (NFR10.5). Events with actor {@code system}.
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
public class MidnightJob {

    private static final Logger log = LoggerFactory.getLogger(MidnightJob.class);
    static final String LOCK_NAME = "bots-midnight";

    private final JdbcBotRepository bots;
    private final LockOrder locks;
    private final BotAudit audit;
    private final LockingTaskExecutor shedLock;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public MidnightJob(
            JdbcBotRepository bots,
            LockOrder locks,
            BotAudit audit,
            LockingTaskExecutor shedLock,
            Clock clock,
            PlatformTransactionManager manager) {
        this.bots = bots;
        this.locks = locks;
        this.audit = audit;
        this.shedLock = shedLock;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** {@code ran} is false when another instance held the lock. */
    public record Round(boolean ran, int cleared, int resumed, int waiting, int failed) {}

    /** True at 00:00:00 UTC (to the second): the instant the scheduler fires the job. */
    public boolean isMidnight(Instant instant) {
        return LocalTime.ofInstant(instant, ZoneOffset.UTC).toSecondOfDay() == 0;
    }

    public Round runOnce() {
        return CorrelationId.withNew(() -> {
            LockConfiguration lock =
                    new LockConfiguration(clock.instant(), LOCK_NAME, Duration.ofMinutes(30), Duration.ZERO);
            try {
                LockingTaskExecutor.TaskResult<Round> result = shedLock.executeWithLock(this::round, lock);
                return result.wasExecuted() ? result.getResult() : new Round(false, 0, 0, 0, 0);
            } catch (RuntimeException | Error unexpected) {
                throw unexpected;
            } catch (Throwable checked) {
                throw new IllegalStateException("The midnight round failed with a checked exception", checked);
            }
        });
    }

    private Round round() {
        int cleared = 0;
        int resumed = 0;
        int waiting = 0;
        int failed = 0;
        for (BotRef ref : bots.botsWith(PauseReason.DAILY_LOSS)) {
            try {
                Optional<MidnightChange> change = clear(ref);
                if (change.isPresent()) {
                    cleared++;
                    resumed += change.get().resumed() ? 1 : 0;
                    waiting += change.get().waitingForSlot() ? 1 : 0;
                }
            } catch (RuntimeException failure) {
                failed++;
                log.error(
                        "Midnight change failed: botId={}, error={}",
                        ref.botId(),
                        failure.getClass().getSimpleName());
            }
        }
        log.info("Midnight round: cleared={}, resumed={}, waiting={}, failed={}", cleared, resumed, waiting, failed);
        return new Round(true, cleared, resumed, waiting, failed);
    }

    private Optional<MidnightChange> clear(BotRef ref) {
        return transactions.execute(status -> {
            locks.applyCommandTimeouts();
            locks.lockOwner(ref.scope());
            Optional<Bot> locked = locks.lockBot(ref.botId(), ref.scope());
            if (locked.isEmpty()) {
                return Optional.empty();
            }
            MidnightChange change = BotStateMachine.midnight(locked.get().status(), bots.countActive(ref.scope()));
            if (!change.dailyLossCleared()) {
                return Optional.empty();
            }
            Instant now = clock.instant();
            bots.save(locked.get().withStatus(change.after(), now));
            audit.bySystem(ref, AuditActions.BOT_DAILY_LOSS_CLEARED, Map.of());
            if (change.resumed()) {
                audit.bySystem(ref, AuditActions.BOT_RESUMED, Map.of());
            } else if (change.waitingForSlot()) {
                audit.bySystem(ref, AuditActions.BOT_PAUSED, Map.of("reason", PauseReason.WAITING_ACTIVE_SLOT.name()));
            }
            return Optional.of(change);
        });
    }
}
