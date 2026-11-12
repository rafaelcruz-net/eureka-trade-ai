package com.memetrade.identity.infra;

import com.memetrade.identity.domain.terms.CurrentTerms;
import com.memetrade.shared.config.ProcessRole;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.springframework.stereotype.Component;

/**
 * u3-reacceptance, Passo 15 (Build and Test loop-back 1; NFR3.2, NFR3.4, NFR2.5): when the api is ready, warms the path
 * of the reacceptance lock query on every connection of the pool, so the first burst of guests after a start does not
 * pay for it inside the 5 ms of the lock check.
 *
 * <ul>
 *   <li>borrows the connections of the pool at the same time (up to its maximum size), so each one is a different
 *       connection, and runs on each {@link #EXECUTIONS_PER_CONNECTION} times exactly the text that
 *       {@link JdbcTermsAcceptanceLookup#SQL} becomes after the named parameters are replaced (the same text, the same
 *       parameter types), with random ids and the current version: pgjdbc prepares a query on the server after 5
 *       executions on one connection, and the JVM compiles the path; the results are discarded;
 *   <li>never goes through {@link JdbcTermsAcceptanceLookup}: the timer {@code memetrade.terms.reacceptance.check}
 *       keeps measuring only the checks of the requests;
 *   <li>keeps nothing in memory (NFR3.4): no field but its dependencies, no cache of acceptances;
 *   <li>runs before the api accepts traffic (the readiness state changes after this event); a failure becomes one
 *       {@code WARN} with the exception class only and never stops the start (the lock still fails closed, NFR2.5).
 * </ul>
 *
 * Only in the api process and where the start-up jobs run: never in the worker (no lock there) nor in the
 * {@code migrate} profile (scheduling off); integration tests drive {@link #warmUp()} directly.
 */
@Component
@ConditionalOnProperty(name = "memetrade.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@ProcessRole(ProcessRole.Kind.API)
public class TermsReacceptanceWarmup {

    /** One more than the prepare threshold of pgjdbc (5), so the server-prepared statement is in use. */
    static final int EXECUTIONS_PER_CONNECTION = 6;

    /** The text the lookup sends: {@link JdbcTermsAcceptanceLookup#SQL} with its three named parameters as {@code ?}. */
    static final String JDBC_SQL = NamedParameterUtils.parseSqlStatementIntoString(JdbcTermsAcceptanceLookup.SQL);

    /** Connection timeout assumed when the data source is not a Hikari pool. */
    private static final long DEFAULT_WAIT_MILLIS = 2000;

    /** Limit of each warm-up query; the start never hangs on a slow database. */
    private static final int QUERY_TIMEOUT_SECONDS = 2;

    private static final Logger log = LoggerFactory.getLogger(TermsReacceptanceWarmup.class);

    private final DataSource dataSource;
    private final CurrentTerms terms;

    public TermsReacceptanceWarmup(DataSource dataSource, CurrentTerms terms) {
        this.dataSource = dataSource;
        this.terms = terms;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        warmUp();
    }

    /**
     * Warms the connections; returns how many were warmed (0 after a failure, which is logged once and never thrown).
     */
    int warmUp() {
        try {
            int warmed = warmConnections(terms.current().version());
            log.info("Reacceptance lock query warmed on {} connections", warmed);
            return warmed;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            warnFailure(interrupted);
        } catch (SQLException | RuntimeException failure) {
            warnFailure(failure);
        }
        return 0;
    }

    private static void warnFailure(Exception failure) {
        log.warn(
                "Warm-up of the reacceptance lock query failed ({}); the api starts without it",
                failure.getClass().getSimpleName());
    }

    private int warmConnections(String version) throws SQLException, InterruptedException {
        int connections = poolSize();
        long waitMillis = waitMillis();
        CountDownLatch borrowed = new CountDownLatch(connections);
        List<Future<?>> tasks = new ArrayList<>(connections);
        try (ExecutorService workers = Executors.newFixedThreadPool(connections)) {
            for (int i = 0; i < connections; i++) {
                tasks.add(workers.submit(() -> {
                    warmOneConnection(version, borrowed, waitMillis);
                    return null;
                }));
            }
            return awaitAll(tasks);
        }
    }

    /** Waits for every task; the first failure is rethrown with its own class, after the others ended. */
    private static int awaitAll(List<Future<?>> tasks) throws SQLException, InterruptedException {
        Exception first = null;
        int warmed = 0;
        for (Future<?> task : tasks) {
            try {
                task.get();
                warmed++;
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof Error error) {
                    throw error;
                }
                if (first == null) {
                    first = cause instanceof Exception exception ? exception : new IllegalStateException(cause);
                }
            }
        }
        if (first instanceof SQLException sql) {
            throw sql;
        }
        if (first instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (first instanceof InterruptedException interrupted) {
            throw interrupted;
        }
        if (first != null) {
            throw new IllegalStateException(first);
        }
        return warmed;
    }

    /**
     * Holds one connection until every task holds one, so the tasks warm different connections. The wait is half the
     * connection timeout of the pool: when a start-up job holds some connections, the tasks still waiting for one get
     * it once the others release theirs, before their own timeout.
     */
    private void warmOneConnection(String version, CountDownLatch borrowed, long waitMillis)
            throws SQLException, InterruptedException {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } finally {
            borrowed.countDown();
        }
        try (connection) {
            borrowed.await(Math.max(1, waitMillis / 2), TimeUnit.MILLISECONDS);
            for (int i = 0; i < EXECUTIONS_PER_CONNECTION; i++) {
                runLockQuery(connection, version);
            }
        }
    }

    /** Same text and parameter binding as the lookup (setObject for the ids, setString for the version). */
    private static void runLockQuery(Connection connection, String version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(JDBC_SQL)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, version);
            statement.setObject(3, UUID.randomUUID());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
            }
        }
    }

    private int poolSize() throws SQLException {
        return hikari() == null ? 1 : Math.max(1, hikari().getMaximumPoolSize());
    }

    private long waitMillis() throws SQLException {
        return hikari() == null ? DEFAULT_WAIT_MILLIS : hikari().getConnectionTimeout();
    }

    private HikariDataSource hikari() throws SQLException {
        return dataSource.isWrapperFor(HikariDataSource.class) ? dataSource.unwrap(HikariDataSource.class) : null;
    }
}
