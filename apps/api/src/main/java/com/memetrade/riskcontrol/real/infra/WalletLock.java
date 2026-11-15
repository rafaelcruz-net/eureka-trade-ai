package com.memetrade.riskcontrol.real.infra;

import com.memetrade.riskcontrol.api.RealGateUnavailable;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 261113-trading-real-bot U3 (BR6.1, U3-NFR3.3, U3-NFR4.8, U3-NFR8.5): the transaction lock of the bot wallet of one
 * person, {@code pg_try_advisory_xact_lock(<U3 key space>, hashtext(userId))}, tried every 20 ms for at most 1 s on the
 * connection of the caller's transaction. It lasts until that transaction ends. A try never raises a database error and
 * never changes a setting of the session, so after 1 s the transaction is still usable and the caller decides to roll
 * back; the evaluation then fails closed with {@link RealGateUnavailable} ({@code lock}).
 *
 * <p>The key is the PERSON, not the bot wallet: a person has one ACTIVE bot wallet (BR1.4 of the U1), so the lock is the
 * same, and it can be taken before anything is read, as F1 asks (the id of the wallet is read only under the lock).
 */
@Component
public class WalletLock {

    /** Key space of the U3 ("U3RS" in ASCII); the other advisory locks of the project use one 64-bit key. */
    static final int KEY_SPACE = 0x55335253;

    static final Duration POLL = Duration.ofMillis(20);
    static final Duration BUDGET = Duration.ofSeconds(1);

    private final JdbcClient jdbc;

    public WalletLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes the lock of the wallet of {@code userId} in the current transaction.
     *
     * @return how long it waited
     * @throws IllegalStateException without an active transaction (the lock would end with the statement)
     * @throws RealGateUnavailable after 1 s without the lock
     */
    public Duration acquire(UUID userId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The wallet lock needs the transaction of the caller");
        }
        long started = System.nanoTime();
        long deadline = started + BUDGET.toNanos();
        while (true) {
            if (tryLock(userId)) {
                return Duration.ofNanos(System.nanoTime() - started);
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new RealGateUnavailable(RealGateUnavailable.Dependency.LOCK);
            }
            pause(Math.min(left, POLL.toNanos()));
        }
    }

    private boolean tryLock(UUID userId) {
        return Boolean.TRUE.equals(jdbc.sql("select pg_try_advisory_xact_lock(:space, hashtext(:user))")
                .param("space", KEY_SPACE)
                .param("user", userId.toString())
                .query(Boolean.class)
                .single());
    }

    private static void pause(long nanos) {
        try {
            Thread.sleep(Duration.ofNanos(nanos));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RealGateUnavailable(RealGateUnavailable.Dependency.LOCK, interrupted);
        }
    }
}
