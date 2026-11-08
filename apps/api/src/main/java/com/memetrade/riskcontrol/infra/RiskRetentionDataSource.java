package com.memetrade.riskcontrol.infra;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Connection pool of U6 for the retention login (infrastructure-specification.md): 1 connection, none kept idle, a 30 s
 * wait, opened only while a purge round runs. The login is the retention login of U2, member of
 * {@code risk_retention} since U6; this pool is NOT the pool of U2, so the trail purge (03:00) and this purge (03:30)
 * never compete for one connection. Deliberately not a {@link javax.sql.DataSource} bean; an ArchUnit rule lets only
 * {@code RiskEvaluationPurgeJob} receive it.
 */
public final class RiskRetentionDataSource implements DisposableBean {

    private final HikariDataSource pool;
    private final JdbcClient jdbc;

    public RiskRetentionDataSource(HikariDataSource pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.jdbc = JdbcClient.create(pool);
    }

    /** Statements run in auto-commit: each batch DELETE is its own transaction (NFR10.6). */
    public JdbcClient jdbc() {
        return jdbc;
    }

    /**
     * Runs {@code work} on the retention connection inside ONE transaction that is always rolled back, never committed,
     * whether {@code work} returns or throws; the connection then goes back to the pool in auto-commit. Used only by the
     * warm-up of the purge (Build and Test loop 3, F10): it executes the DELETE of batch 1 so that the triggers, the
     * foreign-key checks and the index path run once, and undoes it. No new privilege: the same login, the same DELETE.
     */
    public <T> T inRolledBackTransaction(Function<JdbcClient, T> work) {
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                return work.apply(JdbcClient.create(new SingleConnectionDataSource(connection, true)));
            } finally {
                try {
                    connection.rollback();
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        } catch (SQLException failure) {
            throw new DataAccessResourceFailureException(
                    "The retention connection failed around the rolled-back transaction", failure);
        }
    }

    public int maximumPoolSize() {
        return pool.getMaximumPoolSize();
    }

    public long connectionTimeoutMillis() {
        return pool.getConnectionTimeout();
    }

    @Override
    public void destroy() {
        pool.close();
    }
}
