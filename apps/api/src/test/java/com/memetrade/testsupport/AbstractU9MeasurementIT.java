package com.memetrade.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfiguration;

/**
 * Base of the U9 measurements (NFR3.1, NFR1.1): everything of {@link AbstractU9IT}, but on a database of its own in
 * the same PostgreSQL 17 container, created by the superuser, prepared by the versioned role bootstrap and migrated by
 * the OWNER through the production db-migrate path, exactly like {@link AbstractPostgresIT} prepares the shared one
 * (the common pattern lives in {@link OwnTestDatabase}, shared with {@link U7VolumeDatabase}).
 *
 * <p>Review R-02 of U9: the measurements seed about 460 bots with their commands, audit events, PAPER orders and
 * ledger entries. In the shared database that volume changed the planner statistics read by other suites
 * ({@code JdbcAuditEventReaderIT} of U2). The volume cannot be removed afterwards: the ledger is append-only (an
 * UPDATE or DELETE of an entry fails by design) and the audit trail only lets the retention login delete events older
 * than 12 months. So the volume is never written to the shared database at all: these classes get a Spring context of
 * their own (same beans, same logins), connected to this database, and no other test class ever reads it.
 *
 * <p>The URL is set by an initializer rather than a {@code @DynamicPropertySource}: the dynamic property of the shared
 * database is declared on {@link AbstractPostgresIT} and Spring registers superclass methods after subclass ones, so a
 * subclass {@code @DynamicPropertySource} cannot replace it. The initializer runs after the dynamic properties and
 * adds its source in front of them.
 */
@ContextConfiguration(initializers = AbstractU9MeasurementIT.MeasurementDatabase.class)
public abstract class AbstractU9MeasurementIT extends AbstractU9IT {

    private static final String PREFIX = "u9_measure_";

    /**
     * The JDBC URL of the measurement database, created and migrated on first use (once per JVM) by
     * {@link OwnTestDatabase}.
     */
    static String measurementUrl() {
        return OwnTestDatabase.url(PREFIX);
    }

    /** Points the application pools (application and retention logins) at the measurement database. */
    public static class MeasurementDatabase implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(new MapPropertySource(
                            "u9MeasurementDatabase", Map.of("spring.datasource.url", measurementUrl())));
        }
    }

    /**
     * Refreshes the planner statistics of the measurement database after a test seeded its volume, as autovacuum does
     * in production after that many inserts. The database is new, so without it the first plans are made without any
     * statistics. Runs with the OWNER login, which owns every table of the migrations.
     */
    protected static void refreshPlannerStatistics() {
        OwnTestDatabase.analyze(PREFIX);
    }

    /** Fails fast if the context ever falls back to the shared database (the isolation is the point of this base). */
    @BeforeEach
    void runsOnTheMeasurementDatabase() {
        String database =
                jdbc.sql("select current_database()").query(String.class).single();
        assertThat(database).startsWith(PREFIX);
    }
}
