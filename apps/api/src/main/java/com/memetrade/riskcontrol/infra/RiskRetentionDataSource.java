package com.memetrade.riskcontrol.infra;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Objects;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.simple.JdbcClient;

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
