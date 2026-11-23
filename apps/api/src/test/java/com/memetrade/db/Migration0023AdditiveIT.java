package com.memetrade.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 261121-paper-real-unico U2, Etapa 2 (written before the migration 0023; NFR2.3, NFR3.6, reliability-design.md
 * "Migração aditiva e rollback"): the migration 0023 only creates new objects. On a database of its own with REAL rows,
 * the columns, the triggers and the privileges of {@code real_ledger_entry} and {@code real_order} and their row counts
 * are the same without the 0023 and with it, so the previous image keeps working on the migrated database; and the
 * migration text has no ALTER TABLE, UPDATE, DELETE nor INSERT statement.
 */
class Migration0023AdditiveIT extends AbstractPostgresIT {

    private static final Path MIGRATION =
            Path.of("src", "main", "resources", "db", "changelog", "changes", "0023-real-entry-slot.sql");

    private static final List<String> TOUCHED = List.of("real_ledger_entry", "real_order");

    @Test
    void theMigrationTextOnlyCreatesGrantsAndRevokes() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
        String statements = sql.replaceAll("(?m)^--.*$", "");

        assertThat(Pattern.compile("\\bALTER\\s+(TABLE|INDEX|SEQUENCE)\\b")
                        .matcher(statements)
                        .find())
                .isFalse();
        assertThat(Pattern.compile("(?m)(^|;)\\s*UPDATE\\s").matcher(statements).find())
                .isFalse();
        assertThat(Pattern.compile("\\bDELETE\\s+FROM\\b").matcher(statements).find())
                .isFalse();
        assertThat(Pattern.compile("\\bINSERT\\s+INTO\\b").matcher(statements).find())
                .isFalse();
        assertThat(statements).contains("CREATE TABLE REAL_ENTRY_SLOT");
        assertThat(statements).contains("CREATE INDEX CONCURRENTLY REAL_LEDGER_ENTRY_WALLET_CREATED_IDX");
    }

    @Test
    void everyChangesetDeclaresExactlyOneRollbackAndOnlyTheIndexRunsOutsideATransaction() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        List<String> changesets =
                Pattern.compile("(?m)^--changeset ").splitAsStream(sql).skip(1).toList();

        assertThat(changesets).hasSize(4);
        assertThat(changesets).allSatisfy(changeset -> assertThat(Pattern.compile("(?m)^--rollback ")
                        .matcher(changeset)
                        .results()
                        .count())
                .isEqualTo(1));
        assertThat(changesets.stream().filter(c -> c.contains("runInTransaction:false")))
                .singleElement()
                .satisfies(c -> assertThat(c)
                        .startsWith("memetrade:0023-real-ledger-entry-wallet-created-idx")
                        .doesNotContain("splitStatements:false"));
    }

    @Test
    @SuppressWarnings("deprecation")
    void theExistingRealTablesAreTheSameWithoutAndWithTheMigration() throws Exception {
        String dbName = "u2add_" + UUID.randomUUID().toString().replace("-", "");
        connect(SUPERUSER).sql("create database " + dbName).update();
        String url = POSTGRES.getJdbcUrl().replace("/memetrade", "/" + dbName);
        bootstrap(dbName);
        migrate(url);
        JdbcClient app = connect(APP, url);
        seedRealRows(app);

        try (Connection connection = DriverManager.getConnection(url, OWNER.user(), OWNER.password())) {
            Database database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            Liquibase liquibase =
                    new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), database);
            liquibase.rollback(AnonymizationMigrationIT.changesetsFrom("0023-real-entry-slot.sql"), "");
        }
        JdbcClient owner = connect(OWNER, url);
        assertThat(tableExists(owner, "real_entry_slot")).isFalse();
        Map<String, Object> before = snapshot(owner);

        migrate(url);

        assertThat(tableExists(owner, "real_entry_slot")).isTrue();
        assertThat(snapshot(owner)).isEqualTo(before);
    }

    private static void seedRealRows(JdbcClient app) {
        TestAccounts.Seeded person =
                TestAccounts.betaUser(app, TestWallet.generate().address());
        UUID wallet = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.parse("2026-11-20T10:00:00Z"));
        app.sql("""
                        insert into bot_wallet_account (bot_wallet_id, tenant_id, user_id, public_address,
                          main_wallet_address, network, created_at)
                        values (:id, :tenant, :user, :address, :main, 'LOCALNET', :at)""")
                .param("id", wallet)
                .param("tenant", person.tenantId())
                .param("user", person.userId())
                .param("address", TestWallet.generate().address())
                .param("main", person.walletAddress())
                .param("at", at)
                .update();
        app.sql("insert into bot_owner (tenant_id, owner_user_id, created_at) values (:t, :u, :at)"
                        + " on conflict do nothing")
                .param("t", person.tenantId())
                .param("u", person.userId())
                .param("at", at)
                .update();
        UUID bot = UUID.randomUUID();
        app.sql("""
                        insert into bot (bot_id, tenant_id, owner_user_id, name, template_id, template_version, state,
                          created_at, updated_at, market)
                        values (:b, :t, :u, 'Real', 'MOMENTUM_VOLUME', 'v1', 'ACTIVE', :at, :at, 'REAL')""")
                .param("b", bot)
                .param("t", person.tenantId())
                .param("u", person.userId())
                .param("at", at)
                .update();
        String signature = SyntheticIds.signature();
        app.sql("""
                        insert into real_order (real_order_id, tenant_id, user_id, bot_id, origin, idempotency_key,
                          side, mint, input_lamports, operation_mode, signing_mode, max_slippage_bps, reason, state,
                          tx_signature, out_base_units, fee_lamports, fee_payer, created_at, updated_at)
                        values (:id, :t, :u, :b, 'BOT', :key, 'BUY', :mint, 1000000, 'AUTO_FULL', 'AUTO_FULL', 100,
                          'migration test', 'CONFIRMED', :sig, 10000000, 5000, 'BOT_WALLET', :at, :at)""")
                .param("id", UUID.randomUUID())
                .param("t", person.tenantId())
                .param("u", person.userId())
                .param("b", bot)
                .param("key", "u2add:" + UUID.randomUUID())
                .param("mint", SyntheticIds.mint())
                .param("sig", signature)
                .param("at", at)
                .update();
        for (int i = 0; i < 3; i++) {
            app.sql("""
                            insert into real_ledger_entry (entry_id, tenant_id, bot_wallet_id, entry_kind, lamports,
                              tx_signature, reference, idempotency_key, created_at)
                            values (:id, :t, :w, 'DEPOSIT', 1000000000, :sig, :ref, :key, :at)""")
                    .param("id", UUID.randomUUID())
                    .param("t", person.tenantId())
                    .param("w", wallet)
                    .param("sig", SyntheticIds.signature())
                    .param("ref", "dep:" + i)
                    .param("key", "u2add-dep:" + UUID.randomUUID())
                    .param("at", at)
                    .update();
        }
    }

    /** Row counts, columns, triggers and privileges of the REAL tables the previous image reads and writes. */
    private static Map<String, Object> snapshot(JdbcClient owner) {
        Map<String, Object> snapshot = new java.util.TreeMap<>();
        for (String table : TOUCHED) {
            snapshot.put(
                    table + ".rows",
                    owner.sql("select count(*) from " + table).query(Long.class).single());
            snapshot.put(
                    table + ".columns",
                    owner.sql("""
                            select column_name || ':' || data_type || ':' || is_nullable || ':'
                              || coalesce(column_default, '')
                            from information_schema.columns where table_schema = 'public' and table_name = :t
                            order by column_name""").param("t", table).query(String.class).list());
            snapshot.put(
                    table + ".triggers",
                    owner.sql("""
                            select tgname || ':' || pg_get_triggerdef(t.oid) from pg_trigger t
                            where t.tgrelid = cast(:t as regclass) and not t.tgisinternal order by tgname""").param("t", table).query(String.class).list());
            snapshot.put(
                    table + ".privileges",
                    owner.sql("""
                            select grantee || ':' || privilege_type from information_schema.role_table_grants
                            where table_schema = 'public' and table_name = :t order by grantee, privilege_type""").param("t", table).query(String.class).list());
            snapshot.put(
                    table + ".constraints",
                    owner.sql("""
                            select conname || ':' || pg_get_constraintdef(oid) from pg_constraint
                            where conrelid = cast(:t as regclass) order by conname""").param("t", table).query(String.class).list());
        }
        return snapshot;
    }

    private static boolean tableExists(JdbcClient owner, String table) {
        return owner.sql("select to_regclass(:t) is not null")
                .param("t", table)
                .query(Boolean.class)
                .single();
    }
}
