package com.memetrade.feedback.infra;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Objects;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Connection pool of U8 for the retention login (infrastructure-specification.md R-01 and R-08): 1 connection, none kept
 * idle, opened only while a purge round runs. The login is the retention login of U2, member of
 * {@code feedback_retention} since U8; this pool is NOT the pool of U2 nor of U6, so the three purges never compete for
 * one connection. Deliberately not a {@link javax.sql.DataSource} bean; an ArchUnit rule lets only
 * {@code FeedbackPurgeJob} receive it.
 */
public final class FeedbackRetentionDataSource implements DisposableBean {

    private final HikariDataSource pool;
    private final JdbcClient jdbc;

    public FeedbackRetentionDataSource(HikariDataSource pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.jdbc = JdbcClient.create(pool);
    }

    /** Statements run in auto-commit: each batch DELETE is its own transaction (NFR2.6). */
    public JdbcClient jdbc() {
        return jdbc;
    }

    public int maximumPoolSize() {
        return pool.getMaximumPoolSize();
    }

    @Override
    public void destroy() {
        pool.close();
    }
}
