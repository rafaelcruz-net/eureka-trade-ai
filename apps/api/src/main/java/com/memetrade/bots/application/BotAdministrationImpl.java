package com.memetrade.bots.application;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.bots.api.BotAdministration;
import com.memetrade.bots.api.BotState;
import com.memetrade.bots.api.PauseReason;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.BotStateMachine;
import com.memetrade.bots.domain.BotStateMachine.Change;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.infra.BotAudit;
import com.memetrade.bots.infra.BotProperties;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.bots.infra.LockOrder;
import com.memetrade.decision.infra.JdbcDecisionRepository;
import com.memetrade.identity.api.Principal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * C16 (BR7.1 to BR7.3): the system pause of U5, its lifting, the counts and the summary of the dashboard. The state of
 * the kill switch is written FIRST, in its own transaction with the row locked exclusively, and committed; from then on
 * start and resume are refused and the cycle skips. Then the bots are changed in batches selected among the committed
 * ACTIVE and PAUSED bots (a start that committed just before is in the batch), each bot in its own transaction with its
 * lock. A failed bot is returned in {@code failed}, never hidden; U8 repeats until nothing fails.
 */
@Service
public class BotAdministrationImpl implements BotAdministration {

    private static final Logger log = LoggerFactory.getLogger(BotAdministrationImpl.class);

    private final JdbcBotRepository bots;
    private final JdbcDecisionRepository decisions;
    private final LockOrder locks;
    private final BotAudit audit;
    private final Clock clock;
    private final int batchSize;
    private final TransactionTemplate transactions;

    public BotAdministrationImpl(
            JdbcBotRepository bots,
            JdbcDecisionRepository decisions,
            LockOrder locks,
            BotAudit audit,
            Clock clock,
            BotProperties properties,
            PlatformTransactionManager transactionManager) {
        this.bots = bots;
        this.decisions = decisions;
        this.locks = locks;
        this.audit = audit;
        this.clock = clock;
        this.batchSize = properties.systemPauseBatchSize();
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public SystemPauseResult applySystemPause(String correlationId) {
        writeState(true, correlationId);
        return batches(
                true,
                bots::activeOrPaused,
                bot -> BotStateMachine.pauseBy(bot.status(), PauseReason.SYSTEM),
                AuditActions.BOT_PAUSED,
                Map.of("reason", PauseReason.SYSTEM.name()));
    }

    @Override
    public SystemPauseResult liftSystemPause(String correlationId) {
        writeState(false, correlationId);
        return batches(
                false,
                bots::withSystemReason,
                bot -> BotStateMachine.liftSystem(bot.status()),
                AuditActions.BOT_SYSTEM_PAUSE_LIFTED,
                Map.of());
    }

    @Override
    public Map<BotState, Long> countByState() {
        return bots.countByState();
    }

    @Override
    public BotDashboardSummary summaryFor(Principal principal) {
        Map<BotState, Long> counts = bots.countByState(TenantScope.of(principal));
        Optional<LastDecision> last = decisions
                .last(principal.tenantId(), principal.userId())
                .map(decision -> new LastDecision(
                        decision.botId(),
                        decision.action(),
                        decision.tokenMint(),
                        decision.summary(),
                        decision.createdAt()));
        return new BotDashboardSummary(
                counts.get(BotState.ACTIVE), counts.get(BotState.PAUSED), BotStateMachine.ACTIVE_LIMIT, last);
    }

    @Override
    public boolean systemPaused() {
        return bots.systemPaused();
    }

    private void writeState(boolean active, String correlationId) {
        transactions.executeWithoutResult(status -> {
            locks.applyCommandTimeouts();
            locks.lockSystemPauseExclusive();
            bots.setSystemPaused(active, correlationId == null ? "unknown" : correlationId, clock.instant());
        });
    }

    private SystemPauseResult batches(
            boolean systemPaused,
            BatchSource source,
            Function<Bot, Change> rule,
            String action,
            Map<String, String> details) {
        int changed = 0;
        List<UUID> failed = new ArrayList<>();
        Optional<UUID> after = Optional.empty();
        while (true) {
            List<BotRef> batch = source.next(after, batchSize);
            for (BotRef ref : batch) {
                try {
                    if (applyTo(ref, rule, action, details)) {
                        changed++;
                    }
                } catch (RuntimeException failure) {
                    log.error(
                            "System pause change failed: botId={}, cause={}",
                            ref.botId(),
                            failure.getClass().getSimpleName());
                    failed.add(ref.botId());
                }
            }
            if (batch.size() < batchSize) {
                break;
            }
            after = Optional.of(batch.getLast().botId());
        }
        log.info(
                "System pause batches done: systemPaused={}, changed={}, failed={}",
                systemPaused,
                changed,
                failed.size());
        return new SystemPauseResult(systemPaused, changed, failed);
    }

    /** One bot in its own transaction with its lock; a bot already in the target state does not change. */
    private boolean applyTo(BotRef ref, Function<Bot, Change> rule, String action, Map<String, String> details) {
        Boolean changed = transactions.execute(status -> {
            locks.applyCommandTimeouts();
            Optional<Bot> locked = locks.lockBot(ref.botId(), ref.scope());
            if (locked.isEmpty() || !isActiveOrPaused(locked.get())) {
                return false;
            }
            Change change = rule.apply(locked.get());
            if (!change.changed()) {
                return false;
            }
            Bot next = locked.get().withStatus(change.after(), clock.instant());
            bots.save(next);
            audit.bySystem(ref, action, details);
            return true;
        });
        return Boolean.TRUE.equals(changed);
    }

    private static boolean isActiveOrPaused(Bot bot) {
        return bot.status().state() == BotState.ACTIVE || bot.status().state() == BotState.PAUSED;
    }

    @FunctionalInterface
    private interface BatchSource {
        List<BotRef> next(Optional<UUID> after, int limit);
    }
}
