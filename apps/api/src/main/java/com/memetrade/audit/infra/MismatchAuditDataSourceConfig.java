package com.memetrade.audit.infra;

import com.memetrade.shared.config.ProcessRole;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 261121-paper-real-unico U2 (NFR1.16, NFR3.15; infrastructure-specification.md "Configuração nova"): the pool
 * {@code pnl-mismatch-audit}, only in the api process (the worker never opens these connections). Same URL and login as
 * the main pool; {@code maximumPoolSize = minimumIdle =} {@code memetrade.pnl.mismatch-audit.pool-size} (1 to 2, checked
 * by the {@code RuntimeSettingsGuard}); the wait for a connection is the constant of {@link MismatchAuditDataSource}.
 * The pool registers its metrics {@code hikaricp_*} with the name {@code pnl-mismatch-audit} itself, because it is not
 * a {@code DataSource} bean. It never fails the start: the first write opens what is missing.
 */
@Configuration(proxyBeanMethods = false)
@ProcessRole(ProcessRole.Kind.API)
public class MismatchAuditDataSourceConfig {

    /** The property of the size of the pool and of the threads of the executor of the write. */
    public static final String POOL_SIZE_PROPERTY = "memetrade.pnl.mismatch-audit.pool-size";

    @Bean
    public MismatchAuditDataSource mismatchAuditDataSource(
            DataSourceProperties main, Environment environment, MeterRegistry meters) {
        int size = environment.getProperty(POOL_SIZE_PROPERTY, Integer.class, 2);
        HikariConfig config = new HikariConfig();
        config.setPoolName(MismatchAuditDataSource.POOL_NAME);
        config.setJdbcUrl(main.determineUrl());
        config.setUsername(main.determineUsername());
        config.setPassword(main.determinePassword());
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(size);
        config.setConnectionTimeout(MismatchAuditDataSource.CONNECTION_TIMEOUT_MILLIS);
        config.setAutoCommit(true);
        config.setMetricRegistry(meters);
        // -1: never fail the start of the api; the readiness does not depend on this pool (NFR4.4).
        config.setInitializationFailTimeout(-1);
        return new MismatchAuditDataSource(new HikariDataSource(config));
    }
}
