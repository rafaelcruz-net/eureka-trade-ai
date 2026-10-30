package com.memetrade.audit.infra;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Objects;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Connection pool of the retention login (1 connection, none kept idle). It is deliberately NOT a
 * {@link javax.sql.DataSource} bean, so Spring Boot keeps auto-configuring the main pool, and only the retention
 * purge receives it (an ArchUnit rule fails the build when anything else depends on this class).
 */
public final class AuditRetentionDataSource implements DisposableBean {

    private final HikariDataSource pool;
    private final JdbcClient jdbc;

    public AuditRetentionDataSource(HikariDataSource pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.jdbc = JdbcClient.create(pool);
    }

    /** Statements run in auto-commit: each batch DELETE is its own transaction (BR3.2). */
    public JdbcClient jdbc() {
        return jdbc;
    }

    @Override
    public void destroy() {
        pool.close();
    }
}
