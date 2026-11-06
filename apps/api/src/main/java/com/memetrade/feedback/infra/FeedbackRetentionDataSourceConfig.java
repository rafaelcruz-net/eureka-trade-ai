package com.memetrade.feedback.infra;

import com.memetrade.shared.config.ProcessRole;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The retention pool of the feedback (infrastructure-specification.md): same URL as the main pool, the retention login
 * of U2 ({@code DB_RETENTION_USER} / {@code DB_RETENTION_PASSWORD}, no new variable), {@code maximumPoolSize=1},
 * {@code minimumIdle=0}. It never connects at start-up, so a wrong retention password only fails the purge round (health
 * DOWN after 48 hours) and never the api. Counted as 1 connection in docker-compose.yml (43 of 100).
 */
@Configuration(proxyBeanMethods = false)
@ProcessRole(ProcessRole.Kind.API)
public class FeedbackRetentionDataSourceConfig {

    static final long CONNECTION_TIMEOUT_MILLIS = 30_000;

    @Bean
    public FeedbackRetentionDataSource feedbackRetentionDataSource(
            DataSourceProperties main,
            @Value("${memetrade.audit.retention-db.username:}") String username,
            @Value("${memetrade.audit.retention-db.password:}") String password) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("memetrade-feedback-retention-pool");
        config.setJdbcUrl(main.determineUrl());
        config.setUsername(username.trim());
        config.setPassword(password);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        config.setAutoCommit(true);
        config.setInitializationFailTimeout(-1);
        return new FeedbackRetentionDataSource(new HikariDataSource(config));
    }
}
