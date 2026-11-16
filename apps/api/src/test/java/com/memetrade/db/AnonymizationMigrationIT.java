package com.memetrade.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Passo 7 of u5-anonymization (NFR5.3; reliability-design.md): the migration 0015 only adds or relaxes, every changeset
 * declares its rollback, every rollback runs on a base WITHOUT an anonymized account (and the migration applies again),
 * and the two CHECK constraints accept the valid rows and refuse the invalid ones.
 */
@Tag("u5-anonymization")
class AnonymizationMigrationIT extends AbstractPostgresIT {

    private static final Path MIGRATION =
            Path.of("src", "main", "resources", "db", "changelog", "changes", "0015-anonymization.sql");

    @Test
    void everyChangesetDeclaresItsRollbackAndTheNotNullRollbackSaysWhenItIsValid() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        Matcher changesets = Pattern.compile("--changeset ").matcher(sql);
        int count = 0;
        while (changesets.find()) {
            count++;
        }

        assertThat(count).isEqualTo(6);
        assertThat(sql.split("--rollback ", -1)).hasSize(count + 1);
        assertThat(sql).contains("valid ONLY BEFORE THE FIRST ANONYMIZATION");
        assertThat(sql.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("drop column address")
                .doesNotContain("grant feedback_retention")
                .doesNotContain("create trigger")
                .doesNotContain("drop trigger")
                .doesNotContain("create or replace function feedback_entry_guard");
    }

    @Test
    @SuppressWarnings("deprecation")
    void everyRollbackRunsOnABaseWithoutAnAnonymizedAccountAndTheMigrationAppliesAgain() throws Exception {
        String dbName = "u5anon_" + UUID.randomUUID().toString().replace("-", "");
        connect(SUPERUSER).sql("create database " + dbName).update();
        String url = POSTGRES.getJdbcUrl().replace("/memetrade", "/" + dbName);
        bootstrap(dbName);
        migrate(url);
        JdbcClient owner = connect(OWNER, url);
        TestAccounts.betaUser(connect(APP, url), SyntheticIds.mint());
        assertThat(functionExists(owner)).isTrue();

        try (Connection connection = DriverManager.getConnection(url, OWNER.user(), OWNER.password())) {
            Database database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            Liquibase liquibase =
                    new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), database);
            // Every changeset from 0015 to the last migration, counted from the files in the order of the master
            // changelog, so a later migration never leaves part of 0015 applied (a fixed count went one short with
            // 0017 and two with 0020). This also exercises the rollback of every later migration before 0015.
            int changesets = changesetsFrom("0015-anonymization.sql");
            assertThat(changesets).isGreaterThanOrEqualTo(6);
            assertThatCode(() -> liquibase.rollback(changesets, "")).doesNotThrowAnyException();
        }

        assertThat(functionExists(owner)).isFalse();
        assertThat(nullable(owner, "address")).isEqualTo("NO");
        assertThat(nullable(owner, "provider")).isEqualTo("NO");
        assertThat(owner.sql("""
                        select count(*) from information_schema.columns
                        where table_name = 'wallet' and column_name = 'anonymized_at'
                        """).query(Long.class).single()).isZero();
        assertThat(owner.sql("select count(*) from pg_indexes where indexname = 'login_challenge_wallet_idx'")
                        .query(Long.class)
                        .single())
                .isZero();

        migrate(url);

        assertThat(functionExists(owner)).isTrue();
        assertThat(nullable(owner, "address")).isEqualTo("YES");
    }

    /** The changesets of {@code first} and of every migration after it in the master changelog. */
    static int changesetsFrom(String first) throws java.io.IOException {
        Path changelog = Path.of("src", "main", "resources", "db", "changelog");
        String master = Files.readString(changelog.resolve("db.changelog-master.yaml"), StandardCharsets.UTF_8);
        Matcher includes =
                Pattern.compile("file:\\s*db/changelog/changes/(\\S+\\.sql)").matcher(master);
        int count = 0;
        boolean counting = false;
        while (includes.find()) {
            counting = counting || includes.group(1).equals(first);
            if (counting) {
                String sql = Files.readString(changelog.resolve("changes").resolve(includes.group(1)));
                count += (int) Pattern.compile("(?m)^--changeset ")
                        .matcher(sql)
                        .results()
                        .count();
            }
        }
        if (!counting) {
            throw new IllegalStateException(first + " is not in the master changelog");
        }
        return count;
    }

    @Test
    void theRollbackCountCoversEveryChangesetFromTheAnonymizationMigrationOn() throws Exception {
        // 0015 (6), 0016 (13), 0017 (1), 0018 (8), 0019 (10) and 0020 (1) when this test was written; it only grows.
        assertThat(changesetsFrom("0015-anonymization.sql")).isGreaterThanOrEqualTo(6 + 13 + 1 + 8 + 10 + 1);
    }

    @Test
    void theStatusCheckAcceptsAnonymizedAndRefusesAnythingElse() {
        TestAccounts.Seeded person = TestAccounts.betaUser(jdbc, SyntheticIds.mint());

        assertThatCode(() -> TestAccounts.setUserStatus(jdbc, person.userId(), "ANONYMIZED"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> TestAccounts.setUserStatus(jdbc, person.userId(), "DELETED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theWalletCheckAcceptsTheAnonymizedShapeOnly() {
        assertThatCode(() -> wallet(null, null, Instant.parse("2026-11-10T00:00:00Z")))
                .doesNotThrowAnyException();
        assertThatCode(() -> wallet(SyntheticIds.mint(), "PHANTOM", null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> wallet(null, "PHANTOM", Instant.parse("2026-11-10T00:00:00Z")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> wallet(SyntheticIds.mint(), null, Instant.parse("2026-11-10T00:00:00Z")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> wallet(SyntheticIds.mint(), "PHANTOM", Instant.parse("2026-11-10T00:00:00Z")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> wallet(null, null, null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theBase58CheckStillHoldsWhenTheAddressExists() {
        assertThatThrownBy(() -> wallet("0OIl-not-base58-0OIl-not-base58-0OIl", "PHANTOM", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void wallet(String address, String provider, Instant anonymizedAt) {
        TestAccounts.Seeded person = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        jdbc.sql("""
                        update wallet set address = :address, provider = :provider, anonymized_at = :at
                        where user_id = :user
                        """)
                .param("address", address)
                .param("provider", provider)
                .param("at", anonymizedAt == null ? null : Timestamp.from(anonymizedAt))
                .param("user", person.userId())
                .update();
    }

    private static boolean functionExists(JdbcClient client) {
        return client.sql("select count(*) from pg_proc where proname = 'erase_feedback_of_user'")
                        .query(Long.class)
                        .single()
                > 0;
    }

    private static String nullable(JdbcClient client, String column) {
        return client.sql("""
                        select is_nullable from information_schema.columns
                        where table_name = 'wallet' and column_name = :c
                        """).param("c", column).query(String.class).single();
    }
}
