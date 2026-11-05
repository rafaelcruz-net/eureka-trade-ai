package com.memetrade.riskcontrol.infra;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The retention pool of U6 (infrastructure-specification.md): same URL as the main pool, the retention login of U2
 * ({@code DB_RETENTION_USER} / {@code DB_RETENTION_PASSWORD}, no new variable), {@code maximumPoolSize=1},
 * {@code minimumIdle=0} and a 30 s wait for the connection. It never connects at start-up, so a wrong retention password
 * only fails the purge round (FAILED, health DOWN) and never the API.
 */
@Configuration(proxyBeanMethods = false)
public class RiskRetentionDataSourceConfig {

    static final long CONNECTION_TIMEOUT_MILLIS = 30_000;

    @Bean
    public RiskRetentionDataSource riskRetentionDataSource(
            DataSourceProperties main,
            @Value("${memetrade.audit.retention-db.username:}") String username,
            @Value("${memetrade.audit.retention-db.password:}") String password) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("memetrade-risk-retention-pool");
        config.setJdbcUrl(main.determineUrl());
        config.setUsername(username.trim());
        config.setPassword(password);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        config.setAutoCommit(true);
        // -1: never fail or connect while the context starts; the first statement of a round opens the connection.
        config.setInitializationFailTimeout(-1);
        return new RiskRetentionDataSource(new HikariDataSource(config));
    }
}
