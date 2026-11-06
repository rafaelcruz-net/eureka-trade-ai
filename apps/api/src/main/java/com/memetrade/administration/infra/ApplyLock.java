package com.memetrade.administration.infra;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * The apply lock of the kill switch (reliability-design.md Q1, BR2.8, NFR2.3, review R-03 of the NFR Design). Two
 * levels, ONE budget of {@code budget} (10 s) shared by both:
 *
 * <ol>
 *   <li>a fair {@link ReentrantLock} of the JVM, taken with {@code tryLock}: the advisory lock of PostgreSQL is
 *       re-entrant inside one session, so without it two threads borrowing the same connection would both "hold" it;
 *   <li>the only connection of a dedicated pool (Hikari, 1 connection, borrowed with what is left of the budget, never
 *       the default 30 s) and {@code pg_try_advisory_lock(KEY)} every {@code poll} (200 ms) until the budget ends.
 * </ol>
 *
 * The advisory lock is a SESSION lock: it never expires in the middle of a call; it is released by
 * {@code pg_advisory_unlock} on close, or by PostgreSQL when the session dies. In that last case the holder learns it
 * through {@link Held#stillValid()} and must not trust its result (item 4 of the design).
 */
public class ApplyLock implements DisposableBean {

    /** Fixed key of the advisory lock of U8 ("U8KILL" in ASCII); the lock of the scheduler (ShedLock) is separate. */
    public static final long KEY = 0x55384B494C4CL;

    /** Smallest wait Hikari accepts for a connection. */
    private static final long MIN_BORROW_MILLIS = 250;

    private static final Logger log = LoggerFactory.getLogger(ApplyLock.class);

    private final ReentrantLock jvm = new ReentrantLock(true);
    private final HikariDataSource pool;
    private final Duration budget;
    private final Duration poll;

    public ApplyLock(HikariDataSource pool, Duration budget, Duration poll) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.poll = Objects.requireNonNull(poll, "poll");
    }

    public Duration budget() {
        return budget;
    }

    /** The lock, or empty when the budget ended first (the caller marks the reconciliation, item 3 of the design). */
    public Optional<Held> tryAcquire() {
        long deadline = System.nanoTime() + budget.toNanos();
        try {
            if (!jvm.tryLock(remaining(deadline), TimeUnit.NANOSECONDS)) {
                log.warn("Kill switch apply lock not acquired: level=jvm");
                return Optional.empty();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        Optional<Held> held = Optional.empty();
        try {
            held = advisory(deadline);
            return held;
        } finally {
            if (held.isEmpty()) {
                jvm.unlock();
            }
        }
    }

    private Optional<Held> advisory(long deadline) {
        Connection connection = borrow(deadline);
        if (connection == null) {
            log.warn("Kill switch apply lock not acquired: level=connection");
            return Optional.empty();
        }
        try {
            while (true) {
                if (tryAdvisory(connection)) {
                    return Optional.of(new Session(connection));
                }
                long left = remaining(deadline);
                if (left <= 0) {
                    break;
                }
                Thread.sleep(Math.max(1, Math.min(TimeUnit.NANOSECONDS.toMillis(left), poll.toMillis())));
            }
        } catch (SQLException failure) {
            log.warn(
                    "Kill switch apply lock failed: cause={}",
                    failure.getClass().getSimpleName());
            pool.evictConnection(connection);
            connection = null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (connection != null) {
            close(connection);
        }
        log.warn("Kill switch apply lock not acquired: level=advisory");
        return Optional.empty();
    }

    /** The connection of the dedicated pool within the budget; a dead one is evicted and replaced once. */
    private Connection borrow(long deadline) {
        for (int attempt = 0; attempt < 2; attempt++) {
            long left = TimeUnit.NANOSECONDS.toMillis(remaining(deadline));
            if (left <= 0) {
                return null;
            }
            pool.getHikariConfigMXBean().setConnectionTimeout(Math.max(MIN_BORROW_MILLIS, left));
            try {
                Connection connection = pool.getConnection();
                if (connection.isValid(1)) {
                    return connection;
                }
                pool.evictConnection(connection);
            } catch (SQLException unavailable) {
                log.warn(
                        "Kill switch apply lock has no connection: cause={}",
                        unavailable.getClass().getSimpleName());
                return null;
            }
        }
        return null;
    }

    private static boolean tryAdvisory(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
            statement.setLong(1, KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    private void close(Connection connection) {
        try {
            connection.close();
        } catch (SQLException failure) {
            log.warn(
                    "Kill switch apply lock connection did not close: cause={}",
                    failure.getClass().getSimpleName());
        }
    }

    @Override
    public void destroy() {
        pool.close();
    }

    /** The lock while it is held; closing it releases both levels. */
    public interface Held extends AutoCloseable {

        /** Whether the session that holds the advisory lock is still alive. */
        boolean stillValid();

        @Override
        void close();
    }

    private final class Session implements Held {

        private final Connection connection;
        private boolean closed;

        private Session(Connection connection) {
            this.connection = connection;
        }

        @Override
        public boolean stillValid() {
            try {
                return connection.isValid(1);
            } catch (SQLException failure) {
                return false;
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (stillValid()) {
                    try (PreparedStatement unlock = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                        unlock.setLong(1, KEY);
                        unlock.executeQuery().close();
                    }
                    ApplyLock.this.close(connection);
                } else {
                    // The session died: PostgreSQL already released the advisory lock; drop the dead connection.
                    pool.evictConnection(connection);
                }
            } catch (SQLException failure) {
                log.warn(
                        "Kill switch apply lock was not released cleanly: cause={}",
                        failure.getClass().getSimpleName());
                pool.evictConnection(connection);
            } finally {
                jvm.unlock();
            }
        }
    }
}
