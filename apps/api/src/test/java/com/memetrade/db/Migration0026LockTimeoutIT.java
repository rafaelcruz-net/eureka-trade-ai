package com.memetrade.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.testsupport.AbstractPostgresIT;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 261121-paper-real-unico U4, Etapa 7 (written before the migration; NFR2.7; reliability-design.md "Falha por
 * lock_timeout e repetição"): with a transaction holding {@code bot_cycle}, the first changeset of the 0026 gives up
 * within its local {@code lock_timeout} of 5 s (SQLSTATE 55P03) instead of queueing the cycles behind it: the
 * migration fails in at most 6 s, the rule of today stays whole and no row of the changeset is recorded. Once the
 * transaction ends, the next run applies both changesets.
 */
class Migration0026LockTimeoutIT extends AbstractPostgresIT {

    @Test
    @SuppressWarnings("deprecation")
    void aHeldTableStopsTheMigrationWithinTheLockTimeoutAndTheNextRunAppliesIt() throws Exception {
        String dbName = "u4lock_" + UUID.randomUUID().toString().replace("-", "");
        connect(SUPERUSER).sql("create database " + dbName).update();
        String url = POSTGRES.getJdbcUrl().replace("/memetrade", "/" + dbName);
        bootstrap(dbName);
        migrate(url);
        try (Connection connection = DriverManager.getConnection(url, OWNER.user(), OWNER.password())) {
            Database database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), database)
                    .rollback(AnonymizationMigrationIT.changesetsFrom("0026-bot-cycle-own-quantity-outcomes.sql"), "");
        }
        JdbcClient app = connect(APP, url);
        UUID bot = Migration0026IT.bot(app);
        JdbcClient owner = connect(OWNER, url);
        assertThat(changesetRows(owner)).isZero();

        try (Connection holder = DriverManager.getConnection(url, APP.user(), APP.password())) {
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement()) {
                statement.execute("LOCK TABLE bot_cycle IN ACCESS SHARE MODE");
            }
            // The db-migrate entry point fails (its Liquibase bean does not start): the deployment stops here.
            assertThatThrownBy(() -> migrate(url))
                    .isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("lock timeout");

            // The statements of the first changeset, as Liquibase runs them (one transaction): 55P03 within 6 s.
            try (Connection migration = DriverManager.getConnection(url, OWNER.user(), OWNER.password())) {
                migration.setAutoCommit(false);
                long started = System.nanoTime();
                assertThatThrownBy(() -> {
                            try (Statement statement = migration.createStatement()) {
                                for (String sql : firstChangeset()) {
                                    statement.execute(sql);
                                }
                            }
                        })
                        .isInstanceOfSatisfying(
                                java.sql.SQLException.class,
                                failure -> assertThat(failure.getSQLState()).isEqualTo("55P03"));
                Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
                migration.rollback();
                assertThat(elapsed).isLessThanOrEqualTo(Duration.ofSeconds(6));
            }
            holder.rollback();
        }
        assertThat(changesetRows(owner)).isZero();
        assertThatThrownBy(() -> Migration0026IT.cycle(app, bot, "REAL_NO_OWN_QUANTITY"))
                .as("the rule of today stays whole")
                .isInstanceOf(DataIntegrityViolationException.class);

        migrate(url);

        assertThat(changesetRows(owner)).isEqualTo(2);
        assertThatCode(() -> Migration0026IT.cycle(app, bot, "REAL_NO_OWN_QUANTITY"))
                .doesNotThrowAnyException();
    }

    /** The statements of the first changeset of the 0026, read from the file (comments and rollback left out). */
    private static java.util.List<String> firstChangeset() throws java.io.IOException {
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src", "main", "resources", "db", "changelog", "changes", "0026-bot-cycle-own-quantity-outcomes.sql"));
        String first = sql.split("--changeset ")[1];
        StringBuilder code = new StringBuilder();
        first.lines().skip(1).filter(line -> !line.startsWith("--")).forEach(line -> code.append(line)
                .append('\n'));
        return java.util.Arrays.stream(code.toString().split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .toList();
    }

    private static long changesetRows(JdbcClient owner) {
        return owner.sql("select count(*) from databasechangelog where id like '0026-bot-cycle-outcome-%'")
                .query(Long.class)
                .single();
    }
}
