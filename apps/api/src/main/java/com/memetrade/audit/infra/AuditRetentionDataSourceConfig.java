package com.memetrade.audit.infra;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Second Hikari pool, used only by the retention purge (NFR6.2): same URL as the main pool, the retention login,
 * {@code maximumPoolSize=1} and {@code minimumIdle=0}. It does not connect at startup, so a wrong retention password
 * only fails the purge (FAILED and DEGRADED health) and never the API.
 */
@Configuration(proxyBeanMethods = false)
public class AuditRetentionDataSourceConfig {

    @Bean
    public AuditRetentionDataSource auditRetentionDataSource(
            DataSourceProperties main, AuditRetentionDatabaseProperties retention) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("memetrade-retention-pool");
        config.setJdbcUrl(main.determineUrl());
        config.setUsername(retention.username());
        config.setPassword(retention.password());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(2000);
        config.setAutoCommit(true);
        // -1: never fail or connect while the context starts; the first purge batch opens the connection.
        config.setInitializationFailTimeout(-1);
        return new AuditRetentionDataSource(new HikariDataSource(config));
    }
}
