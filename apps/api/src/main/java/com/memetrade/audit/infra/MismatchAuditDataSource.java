package com.memetrade.audit.infra;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 261121-paper-real-unico U2 (NFR3.15, R-07, R-10; security-design.md "Fiação do pool"): the pool
 * {@code pnl-mismatch-audit} of the event of a reconciliation mismatch of the P&L REAL: 2 connections always open and a
 * wait for a connection of {@value #CONNECTION_TIMEOUT_MILLIS} ms (the floor of HikariCP), so the write never waits for
 * the main pool. Deliberately NOT a {@link DataSource} bean nor a transaction manager bean: Spring Boot keeps configuring
 * the main pool and the main manager, and the readiness never sees this pool. It owns its own transaction manager, its
 * own template ({@code READ_COMMITTED}, {@code REQUIRES_NEW}) and its own {@link JdbcClient}, all on the same pool, so
 * the statements of one transaction run on one connection. Only {@link MismatchAuditWriter} may depend on it (ArchUnit).
 */
public final class MismatchAuditDataSource implements DisposableBean {

    /** The name of the pool in the metrics {@code hikaricp_*}. */
    public static final String POOL_NAME = "pnl-mismatch-audit";

    /** A constant, not a setting: the smallest wait HikariCP accepts (R-04). */
    public static final long CONNECTION_TIMEOUT_MILLIS = 250;

    private final HikariDataSource pool;
    private final DataSourceTransactionManager transactions;
    private final TransactionTemplate template;
    private final JdbcClient jdbc;

    public MismatchAuditDataSource(HikariDataSource pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.transactions = new DataSourceTransactionManager(pool);
        this.template = new TransactionTemplate(transactions);
        this.template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.jdbc = JdbcClient.create(pool);
    }

    /** One write transaction on this pool. */
    TransactionTemplate transactions() {
        return template;
    }

    JdbcClient jdbc() {
        return jdbc;
    }

    /** The pool itself, the key of the connection the transaction binds (checked before writing). */
    DataSource dataSource() {
        return pool;
    }

    @Override
    public void destroy() {
        pool.close();
    }
}
