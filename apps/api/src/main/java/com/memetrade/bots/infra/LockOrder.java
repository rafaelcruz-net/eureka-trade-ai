package com.memetrade.bots.infra;

import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.TenantScope;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The ONLY place of the bots and decision modules that writes a row lock (NFR10.4, reliability-design.md;
 * {@code LockClauseSourceTest}). The global order of BR2.11 is:
 *
 * <ol>
 *   <li>{@code system_pause_state}: exclusive only in the system pause and its lifting, shared in start and resume;
 *   <li>the {@code bot_owner} row of the person: only start, resume and midnight, which count active bots;
 *   <li>the {@code bot} row.
 * </ol>
 *
 * Each method records, in a resource bound to the current transaction, the highest step taken; taking an earlier step
 * after a later one (for example the owner after the bot) is a programming error that throws before any lock is taken,
 * and so is a lock outside a transaction. Nobody locks the owner after the bot, so two transactions never wait on each
 * other in opposite order (no deadlock).
 */
@Component
public class LockOrder {

    /** The steps of BR2.11, in order. */
    enum Step {
        SYSTEM_PAUSE,
        OWNER,
        BOT
    }

    private static final Object PROGRESS_KEY = new Object();

    private final JdbcClient jdbc;
    private final BotProperties properties;

    public LockOrder(JdbcClient jdbc, BotProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** Step 1, shared (start and resume): returns whether the kill switch is on. */
    public boolean lockSystemPauseShared() {
        advance(Step.SYSTEM_PAUSE);
        return locking(() -> jdbc.sql("select active from system_pause_state where id = 1 for share")
                .query(Boolean.class)
                .single());
    }

    /** Step 1, exclusive (system pause and its lifting): returns whether the kill switch is on. */
    public boolean lockSystemPauseExclusive() {
        advance(Step.SYSTEM_PAUSE);
        return locking(() -> jdbc.sql("select active from system_pause_state where id = 1 for update")
                .query(Boolean.class)
                .single());
    }

    /** Step 2: the control row of the person, before counting the active bots under it. */
    public void lockOwner(TenantScope scope) {
        advance(Step.OWNER);
        locking(() -> jdbc.sql(
                                "select 1 from bot_owner where tenant_id = :tenant and owner_user_id = :owner for update")
                        .param("tenant", scope.tenantId())
                        .param("owner", scope.ownerUserId())
                        .query(Integer.class)
                        .optional())
                .orElseThrow(() -> new IllegalStateException("The person has no owner row; a bot is created first"));
    }

    /**
     * u5-anonymization: step 2 when the person may have no bot at all; false (nothing locked) without an owner row.
     */
    public boolean lockOwnerIfPresent(TenantScope scope) {
        advance(Step.OWNER);
        return locking(() -> jdbc.sql(
                                "select 1 from bot_owner where tenant_id = :tenant and owner_user_id = :owner for update")
                        .param("tenant", scope.tenantId())
                        .param("owner", scope.ownerUserId())
                        .query(Integer.class)
                        .optional())
                .isPresent();
    }

    /**
     * u5-anonymization (BR5.6, NFR3.1): step 3 for SEVERAL bots, every bot of the person in ONE statement ordered by
     * {@code bot_id}, so two transactions that lock several bots of the same person never wait in opposite order.
     */
    public java.util.List<Bot> lockOwnerBots(TenantScope scope) {
        advance(Step.BOT);
        return locking(() -> jdbc.sql(JdbcBotRepository.SELECT_BOT
                        + " where b.tenant_id = :tenant and b.owner_user_id = :owner order by b.bot_id for update")
                .param("tenant", scope.tenantId())
                .param("owner", scope.ownerUserId())
                .query(JdbcBotRepository::mapBot)
                .list());
    }

    /** Step 3: the bot row, only within the scope of its owner (BR2.10); empty for anyone else. */
    public Optional<Bot> lockBot(UUID botId, TenantScope scope) {
        advance(Step.BOT);
        return locking(() -> jdbc.sql(JdbcBotRepository.SELECT_BOT
                        + " where b.bot_id = :bot and b.tenant_id = :tenant and b.owner_user_id = :owner for update")
                .param("bot", botId)
                .param("tenant", scope.tenantId())
                .param("owner", scope.ownerUserId())
                .query(JdbcBotRepository::mapBot)
                .optional());
    }

    /**
     * NFR10.7: a lock not obtained within {@code lock_timeout} (SQLSTATE 55P03) becomes a
     * {@link CannotAcquireLockException} (a {@code PessimisticLockingFailureException}): the command answers 503 "tentar
     * de novo" and the cycle rolls back as a technical failure.
     */
    private static <T> T locking(Supplier<T> statement) {
        try {
            return statement.get();
        } catch (UncategorizedSQLException failure) {
            if (failure.getSQLException() != null
                    && LOCK_NOT_AVAILABLE.equals(failure.getSQLException().getSQLState())) {
                throw new CannotAcquireLockException("The lock was not obtained within lock_timeout", failure);
            }
            throw failure;
        }
    }

    private static final String LOCK_NOT_AVAILABLE = "55P03";

    /** NFR10.7: the commands wait at most {@code lock-timeout} for a lock (then 503 "tentar de novo"). */
    public void applyCommandTimeouts() {
        requireTransaction();
        jdbc.sql("set local lock_timeout = '" + properties.lockTimeout().toMillis() + "ms'")
                .update();
    }

    /**
     * BR3.3, NFR10.7: the cycle waits at most {@code lock-timeout} for a lock and runs each statement for at most
     * {@code statement-timeout}; both last until the end of the cycle transaction, so they bind the barrier of U6 and
     * the execution of U7 too (C15).
     */
    public void applyCycleTimeouts() {
        applyCommandTimeouts();
        jdbc.sql("set local statement_timeout = '"
                        + properties.statementTimeout().toMillis() + "ms'")
                .update();
    }

    private void advance(Step step) {
        requireTransaction();
        Progress progress = (Progress) TransactionSynchronizationManager.getResource(PROGRESS_KEY);
        if (progress == null) {
            progress = new Progress();
            TransactionSynchronizationManager.bindResource(PROGRESS_KEY, progress);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(PROGRESS_KEY);
                }
            });
        }
        if (progress.highest != null && step.ordinal() < progress.highest.ordinal()) {
            throw new IllegalStateException(
                    "BR2.11 lock order inverted: " + step + " requested after " + progress.highest);
        }
        progress.highest = step;
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("A row lock of U5 requires an open transaction");
        }
    }

    private static final class Progress {
        private Step highest;
    }
}
