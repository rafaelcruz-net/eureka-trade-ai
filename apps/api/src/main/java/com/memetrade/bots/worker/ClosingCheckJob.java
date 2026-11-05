package com.memetrade.bots.worker;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.bots.api.BotState;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.BotStateMachine;
import com.memetrade.bots.infra.BotAudit;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.bots.infra.LockOrder;
import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.ledger.api.LedgerReadModel.AccountView;
import com.memetrade.ledger.api.PaperEnums.AccountStatus;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * BR2.8, NFR10.8 (F7 step 3): after each slot, every CLOSING_PENDING bot reads the state of its PAPER account through
 * the public interface of U7 ({@code LedgerReadModel.account}), with the bot locked, and becomes STOPPED once the
 * account is CLOSED (event BOT_STOPPED, actor system). It never calls {@code closePositions} again (its answer is kept
 * by the idempotency and never changes) and checks that the account belongs to the bot's tenant and person. Once, even
 * with parallel runs (the bot lock and ShedLock {@code bots-closing-check}).
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
public class ClosingCheckJob {

    private static final Logger log = LoggerFactory.getLogger(ClosingCheckJob.class);
    static final String LOCK_NAME = "bots-closing-check";

    private final JdbcBotRepository bots;
    private final LockOrder locks;
    private final LedgerReadModel ledger;
    private final BotAudit audit;
    private final LockingTaskExecutor shedLock;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public ClosingCheckJob(
            JdbcBotRepository bots,
            LockOrder locks,
            LedgerReadModel ledger,
            BotAudit audit,
            LockingTaskExecutor shedLock,
            Clock clock,
            PlatformTransactionManager manager) {
        this.bots = bots;
        this.locks = locks;
        this.ledger = ledger;
        this.audit = audit;
        this.shedLock = shedLock;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Round(boolean ran, int stopped, int stillPending, int failed) {}

    public Round runOnce() {
        return CorrelationId.withNew(() -> {
            LockConfiguration lock =
                    new LockConfiguration(clock.instant(), LOCK_NAME, Duration.ofMinutes(4), Duration.ZERO);
            try {
                LockingTaskExecutor.TaskResult<Round> result = shedLock.executeWithLock(this::round, lock);
                return result.wasExecuted() ? result.getResult() : new Round(false, 0, 0, 0);
            } catch (RuntimeException | Error unexpected) {
                throw unexpected;
            } catch (Throwable checked) {
                throw new IllegalStateException("The closing check failed with a checked exception", checked);
            }
        });
    }

    private Round round() {
        int stopped = 0;
        int pending = 0;
        int failed = 0;
        for (BotRef ref : bots.botsIn(BotState.CLOSING_PENDING)) {
            try {
                if (check(ref)) {
                    stopped++;
                } else {
                    pending++;
                }
            } catch (RuntimeException failure) {
                failed++;
                log.error(
                        "Closing check failed: botId={}, error={}",
                        ref.botId(),
                        failure.getClass().getSimpleName());
            }
        }
        log.info("Closing check: stopped={}, stillPending={}, failed={}", stopped, pending, failed);
        return new Round(true, stopped, pending, failed);
    }

    private boolean check(BotRef ref) {
        Boolean stopped = transactions.execute(status -> {
            locks.applyCommandTimeouts();
            Optional<Bot> locked = locks.lockBot(ref.botId(), ref.scope());
            if (locked.isEmpty() || locked.get().status().state() != BotState.CLOSING_PENDING) {
                return false;
            }
            Optional<AccountView> account = ledger.account(ref.botId());
            if (account.isEmpty() || account.get().status() != AccountStatus.CLOSED) {
                return false;
            }
            if (!account.get().tenantId().equals(ref.scope().tenantId())
                    || !account.get().ownerUserId().equals(ref.scope().ownerUserId())) {
                log.error("Closing check refused: the PAPER account of botId={} is of another owner", ref.botId());
                return false;
            }
            Instant now = clock.instant();
            Bot bot = locked.get();
            bots.save(bot.toBuilder()
                    .status(BotStateMachine.closingCompleted(bot.status()).after())
                    .stoppedAt(now)
                    .updatedAt(now)
                    .build());
            audit.bySystem(ref, AuditActions.BOT_STOPPED, Map.of());
            return true;
        });
        return Boolean.TRUE.equals(stopped);
    }
}
