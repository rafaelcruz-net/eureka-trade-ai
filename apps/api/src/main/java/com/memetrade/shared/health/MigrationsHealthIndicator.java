package com.memetrade.shared.health;

import javax.sql.DataSource;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Readiness component {@code migrations}: DOWN while the Liquibase changelog table is missing or its lock is held
 * (migrations not applied or still running).
 */
@Component("migrations")
public class MigrationsHealthIndicator extends AbstractHealthIndicator {

    private final JdbcClient jdbc;

    public MigrationsHealthIndicator(DataSource dataSource) {
        super("Migration check failed");
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Integer applied = jdbc.sql("select count(*) from databasechangelog")
                .query(Integer.class)
                .single();
        Boolean locked = jdbc.sql("select coalesce(bool_or(locked), false) from databasechangeloglock")
                .query(Boolean.class)
                .single();
        if (applied == null || applied == 0 || Boolean.TRUE.equals(locked)) {
            builder.down().withDetail("applied", applied == null ? 0 : applied).withDetail("locked", locked);
            return;
        }
        builder.up().withDetail("applied", applied);
    }
}
