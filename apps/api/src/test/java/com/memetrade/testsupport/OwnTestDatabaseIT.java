package com.memetrade.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * {@link OwnTestDatabase} gives a class a migrated database of its own without changing the rest of the JVM run.
 *
 * <p>Regression of Build and Test loop-back 1: creating an own database in the middle of the run went through the
 * db-migrate application, which reset Logback with {@code root=WARN}; every Spring context already cached then lost
 * its INFO lines ({@code AuditObservabilityIT} after {@code AuditEventReaderPlanIT} in {@code -Dgroups=u2}).
 */
@Tag("u2")
class OwnTestDatabaseIT {

    private static final String PREFIX = "own_db_check_";

    @Test
    void creatingAnOwnDatabaseKeepsTheLoggingLevelsOfTheRun() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        root.setLevel(Level.INFO);
        try {
            // A prefix of its own, so the database is created here whatever the order of the methods.
            OwnTestDatabase.url("own_db_logging_");

            Logger rootAfter = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            assertThat(rootAfter.getLevel()).isEqualTo(Level.INFO);
        } finally {
            root.setLevel(before);
        }
    }

    @Test
    void theDatabaseIsMigratedAndSeparateFromTheSharedOne() {
        String database = OwnTestDatabase.connect(PREFIX, AbstractPostgresIT.APP)
                .sql("select current_database()")
                .query(String.class)
                .single();
        Long liquibaseRows = OwnTestDatabase.connect(PREFIX, AbstractPostgresIT.OWNER)
                .sql("select count(*) from databasechangelog")
                .query(Long.class)
                .single();

        assertThat(database).startsWith(PREFIX).isNotEqualTo("memetrade");
        assertThat(liquibaseRows).isPositive();
    }

    @Test
    void thePrefixIsCreatedOncePerRun() {
        assertThat(OwnTestDatabase.url(PREFIX)).isEqualTo(OwnTestDatabase.url(PREFIX));
    }
}
