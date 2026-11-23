package com.memetrade.realexits.db;

import static com.memetrade.testsupport.LedgerRows.sqlState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.regex.Pattern;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Step 3 of the U7 of 261113-trading-real-bot, written BEFORE the migration 0022 (infrastructure-specification.md
 * "Banco", U7-NFR3.5 to U7-NFR3.7): what the application login, the retention login and the owner may do on
 * {@code real_conditional_order}; the partial unique index of BR3.1; the trigger of the mutable columns and of the
 * transitions of entities.md; the age trigger of 5 years; the deferral columns of {@code real_decision_request}; and the
 * rollback, which only drops the new table when it is empty. Each statement runs as one login on its own connection.
 */
@Tag("u7-exits-and-fiscal")
class RealExitsMigrationIT extends AbstractPostgresIT {

    private static final Path MIGRATION = Path.of("src/main/resources/db/changelog/changes/0022-real-exits.sql");
    private static final String DENIED = "42501";
    private static final String UNIQUE = "23505";
    private static final String CHECK = "23514";

    private JdbcClient app;
    private JdbcClient retention;
    private UUID tenant;
    private UUID user;
    private UUID wallet;

    @BeforeEach
    void seed() {
        app = connect(APP);
        retention = connect(RETENTION);
        TestAccounts.Seeded person = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        tenant = person.tenantId();
        user = person.userId();
        wallet = UUID.randomUUID();
    }

    private static String state(JdbcClient login, String sql) {
        return sqlState(catchThrowable(() -> login.sql(sql).update()));
    }

    /** A conditional as the application writes it, in {@code state}, finished {@code finalAge} ago when final. */
    private UUID conditional(JdbcClient login, String mint, String state, String finalAge) {
        UUID id = UUID.randomUUID();
        boolean isFinal = finalAge != null;
        login.sql("insert into real_conditional_order (conditional_order_id, tenant_id, user_id, bot_wallet_id,"
                        + " position_mint, kind, stop_price_usd, take_profit_price_usd, amount_base_units, slippage_bps,"
                        + " state, adapter, idempotency_key, cancel_reason, filled_side, final_at, created_at,"
                        + " updated_at) values (:id, :t, :u, :w, :m, 'OCO', 0.00005, 0.0002, 1000000, 200, :state,"
                        + " 'SIMULATED', :key, :reason, :side, case when :final then now() - cast(:age as interval)"
                        + " end, now() - interval '1 minute', now())")
                .param("id", id)
                .param("t", tenant)
                .param("u", user)
                .param("w", wallet)
                .param("m", mint)
                .param("state", state)
                .param("key", id.toString())
                .param("reason", "CANCELLED".equals(state) ? "USER" : null)
                .param("side", "FILLED".equals(state) ? "STOP" : null)
                .param("final", isFinal)
                .param("age", isFinal ? finalAge : "0 seconds")
                .update();
        return id;
    }

    private UUID conditional(String state) {
        return conditional(app, SyntheticIds.mint(), state, null);
    }

    private String stateOf(UUID id) {
        return jdbc.sql("select state from real_conditional_order where conditional_order_id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    @Test
    void theApplicationInsertsReadsAndUpdatesButNeverDeletesNorTruncates() {
        UUID id = conditional("PENDING_SIGNATURE");

        assertThat(app.sql("select count(*) from real_conditional_order where conditional_order_id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single())
                .isOne();
        assertThat(state(
                        app,
                        "update real_conditional_order set state = 'OPEN', jupiter_order_id = 'sim-1'"
                                + " where conditional_order_id = '" + id + "'"))
                .isEqualTo("none");
        assertThat(state(app, "delete from real_conditional_order where conditional_order_id = '" + id + "'"))
                .isEqualTo(DENIED);
        assertThat(state(app, "truncate real_conditional_order")).isEqualTo(DENIED);
    }

    @Test
    void thePositionHasAtMostOneConditionalThatIsNotFinal() {
        String mint = SyntheticIds.mint();
        UUID first = conditional(app, mint, "OPEN", null);

        assertThat(sqlState(catchThrowable(() -> conditional(app, mint, "PENDING_SIGNATURE", null))))
                .isEqualTo(UNIQUE);
        app.sql("update real_conditional_order set state = 'CANCELLING', cancel_reason = 'USER',"
                        + " cancelling_since = now() where conditional_order_id = :id")
                .param("id", first)
                .update();
        assertThat(sqlState(catchThrowable(() -> conditional(app, mint, "OPEN", null))))
                .as("CANCELLING is not final either")
                .isEqualTo(UNIQUE);
        app.sql("update real_conditional_order set state = 'CANCELLED', final_at = now()"
                        + " where conditional_order_id = :id")
                .param("id", first)
                .update();
        assertThat(sqlState(catchThrowable(() -> conditional(app, mint, "OPEN", null))))
                .isEqualTo("none");
    }

    @Test
    void theIdentityOfAConditionalNeverChanges() {
        UUID id = conditional("OPEN");

        for (String change : new String[] {
            "tenant_id = gen_random_uuid()",
            "user_id = gen_random_uuid()",
            "bot_wallet_id = gen_random_uuid()",
            "position_mint = '" + SyntheticIds.mint() + "'",
            "kind = 'STOP'",
            "stop_price_usd = 0.00001",
            "amount_base_units = 5",
            "slippage_bps = 50",
            "idempotency_key = 'other'",
            "created_at = now()"
        }) {
            assertThat(state(
                            app,
                            "update real_conditional_order set " + change + " where conditional_order_id = '" + id
                                    + "'"))
                    .as(change)
                    .isEqualTo(DENIED);
        }
    }

    @Test
    void theStateMovesOnlyAlongTheMachineOfTheEntities() {
        UUID pending = conditional("PENDING_SIGNATURE");
        UUID open = conditional("OPEN");
        UUID cancelling = conditional("CANCELLING");

        assertThat(state(
                        app,
                        "update real_conditional_order set state = 'FILLED', filled_side = 'STOP'"
                                + " where conditional_order_id = '" + pending + "'"))
                .as("PENDING_SIGNATURE never fills")
                .isEqualTo(DENIED);
        assertThat(state(
                        app,
                        "update real_conditional_order set state = 'PENDING_SIGNATURE' where conditional_order_id = '"
                                + open + "'"))
                .isEqualTo(DENIED);
        assertThat(state(
                        app,
                        "update real_conditional_order set state = 'OPEN' where conditional_order_id = '" + cancelling
                                + "'"))
                .as("CANCELLING never goes back to OPEN (BR6.4)")
                .isEqualTo(DENIED);
        assertThat(state(
                        app,
                        "update real_conditional_order set state = 'FILLED', filled_side = 'TAKE_PROFIT',"
                                + " final_at = now() where conditional_order_id = '" + cancelling + "'"))
                .as("BR6.6: the fill wins over the cancellation")
                .isEqualTo("none");
        assertThat(state(
                        app,
                        "update real_conditional_order set state = 'CANCELLED', cancel_reason = 'KILL_SWITCH',"
                                + " final_at = now() where conditional_order_id = '" + pending + "'"))
                .as("BR6.5: PENDING_SIGNATURE is cancelled directly")
                .isEqualTo("none");
        assertThat(stateOf(cancelling)).isEqualTo("FILLED");
    }

    @Test
    void aFinalConditionalNeverChangesAgain() {
        UUID id = conditional(app, SyntheticIds.mint(), "FILLED", "1 day");

        assertThat(state(
                        app,
                        "update real_conditional_order set last_read_at = now() where conditional_order_id = '" + id
                                + "'"))
                .isEqualTo(DENIED);
        assertThat(state(
                        app,
                        "update real_conditional_order set state = 'CANCELLING' where conditional_order_id = '" + id
                                + "'"))
                .isEqualTo(DENIED);
    }

    @Test
    void theValuesOfTheEnumsAndTheAmountsAreChecked() {
        String insert = "insert into real_conditional_order (conditional_order_id, tenant_id, user_id, bot_wallet_id,"
                + " position_mint, kind, stop_price_usd, take_profit_price_usd, amount_base_units, slippage_bps, state,"
                + " adapter, idempotency_key, created_at, updated_at) values (gen_random_uuid(), '" + tenant + "', '"
                + user + "', '" + wallet + "', '" + SyntheticIds.mint() + "', %s, %s, %s, %s, 200, %s, 'SIMULATED',"
                + " gen_random_uuid()::text, now(), now())";

        assertThat(state(app, insert.formatted("'TRAILING'", "0.1", "null", "10", "'OPEN'")))
                .isEqualTo(CHECK);
        assertThat(state(app, insert.formatted("'STOP'", "0.1", "null", "10", "'LOST'")))
                .isEqualTo(CHECK);
        assertThat(state(app, insert.formatted("'STOP'", "null", "null", "10", "'OPEN'")))
                .as("a STOP carries its stop price")
                .isEqualTo(CHECK);
        assertThat(state(app, insert.formatted("'OCO'", "0.1", "null", "10", "'OPEN'")))
                .as("an OCO carries both prices")
                .isEqualTo(CHECK);
        assertThat(state(app, insert.formatted("'STOP'", "0.1", "null", "0", "'OPEN'")))
                .as("the whole position, at least one base unit")
                .isEqualTo(CHECK);
        assertThat(state(app, insert.formatted("'TAKE_PROFIT'", "null", "0.2", "10", "'OPEN'")))
                .isEqualTo("none");
    }

    @Test
    void theIdempotencyKeyIsUniquePerPerson() {
        String insert = "insert into real_conditional_order (conditional_order_id, tenant_id, user_id, bot_wallet_id,"
                + " position_mint, kind, stop_price_usd, amount_base_units, slippage_bps, state, adapter,"
                + " idempotency_key, created_at, updated_at) values (gen_random_uuid(), '" + tenant + "', '" + user
                + "', '" + wallet + "', '%s', 'STOP', 0.1, 10, 200, 'OPEN', 'SIMULATED', 'same-key', now(),"
                + " now())";

        assertThat(state(app, insert.formatted(SyntheticIds.mint()))).isEqualTo("none");
        assertThat(state(app, insert.formatted(SyntheticIds.mint()))).isEqualTo(UNIQUE);
    }

    @Test
    void theIndexesOfTheJobsAndOfTheListExist() {
        assertThat(jdbc.sql("select indexdef from pg_indexes where tablename = 'real_conditional_order'")
                        .query(String.class)
                        .list())
                .anySatisfy(index -> assertThat(index).contains("UNIQUE").contains("(bot_wallet_id, position_mint)"))
                .anySatisfy(index -> assertThat(index).contains("(bot_wallet_id, state)"))
                .anySatisfy(index -> assertThat(index).contains("(user_id, created_at"));
    }

    @Test
    void theRetentionLoginDeletesOnlyFinalConditionalsOlderThanFiveYears() {
        UUID old = conditional(app, SyntheticIds.mint(), "FILLED", "5 years 1 day");
        UUID young = conditional(app, SyntheticIds.mint(), "CANCELLED", "4 years");
        UUID open = conditional("OPEN");

        assertThat(state(retention, "delete from real_conditional_order where conditional_order_id = '" + old + "'"))
                .isEqualTo("none");
        assertThat(state(retention, "delete from real_conditional_order where conditional_order_id = '" + young + "'"))
                .isEqualTo(DENIED);
        assertThat(state(retention, "delete from real_conditional_order where conditional_order_id = '" + open + "'"))
                .isEqualTo(DENIED);
        assertThat(state(
                        retention,
                        "update real_conditional_order set last_read_at = now() where conditional_order_id = '" + open
                                + "'"))
                .isEqualTo(DENIED);
    }

    @Test
    void aPendingDecisionIsDeferredWithItsNextAttemptAndCountAndClosedWithTheNewReasons() {
        UUID bot = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        app.sql("insert into real_decision_request (decision_request_id, tenant_id, user_id, bot_id, decision_id,"
                        + " side, mint, token_base_units, reason, created_at) values (:id, :t, :u, :b, :d, 'SELL', :m,"
                        + " 5, 'sinal', now())")
                .param("id", request)
                .param("t", tenant)
                .param("u", user)
                .param("b", bot)
                .param("d", UUID.randomUUID())
                .param("m", SyntheticIds.mint())
                .update();

        assertThat(jdbc.sql("select attempts from real_decision_request where decision_request_id = :id")
                        .param("id", request)
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(state(
                        app,
                        "update real_decision_request set not_before = now() + interval '30 seconds',"
                                + " attempts = attempts + 1 where decision_request_id = '" + request + "'"))
                .isEqualTo("none");
        assertThat(state(
                        app,
                        "update real_decision_request set mint = 'x' where decision_request_id = '" + request + "'"))
                .isEqualTo(DENIED);
        assertThat(state(
                        app,
                        "update real_decision_request set state = 'DISCARDED', discard_reason = 'EXIT_CANCEL_TIMEOUT',"
                                + " processed_at = now() where decision_request_id = '" + request + "'"))
                .isEqualTo("none");
        assertThat(state(
                        app,
                        "update real_decision_request set attempts = 0 where decision_request_id = '" + request + "'"))
                .as("a closed decision never changes")
                .isEqualTo(DENIED);
    }

    // ----- the rollback, on a database of its own -----

    @Test
    void everyChangesetDeclaresItsRollback() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        int changesets = changesets();

        assertThat(changesets).isGreaterThanOrEqualTo(4);
        assertThat(Pattern.compile("(?m)^--changeset [^\\n]*\\n(?:(?!--changeset )[\\s\\S])*?--rollback ")
                        .matcher(sql)
                        .results()
                        .count())
                .isEqualTo(changesets);
    }

    @Test
    void theRollbackDropsTheEmptyTableKeepsTheDecisionColumnsAndStopsWhenAConditionalExists() throws Exception {
        String dbName = "u7exits_" + UUID.randomUUID().toString().replace("-", "");
        connect(SUPERUSER).sql("create database " + dbName).update();
        String url = POSTGRES.getJdbcUrl().replace("/memetrade", "/" + dbName);
        bootstrap(dbName);
        migrate(url);
        JdbcClient owner = connect(OWNER, url);

        rollback(url, changesetsFromThisMigrationOn());
        assertThat(tableExists(owner, "real_conditional_order")).isFalse();
        assertThat(columnExists(owner, "real_decision_request", "not_before")).isTrue();
        assertThat(columnExists(owner, "real_decision_request", "attempts")).isTrue();

        migrate(url);
        JdbcClient ownApp = connect(APP, url);
        TestAccounts.Seeded person = TestAccounts.betaUser(ownApp, SyntheticIds.mint());
        ownApp.sql("insert into real_conditional_order (conditional_order_id, tenant_id, user_id, bot_wallet_id,"
                        + " position_mint, kind, stop_price_usd, amount_base_units, slippage_bps, state, adapter,"
                        + " idempotency_key, created_at, updated_at) values (gen_random_uuid(), :t, :u,"
                        + " gen_random_uuid(), :m, 'STOP', 0.1, 10, 200, 'OPEN', 'SIMULATED', 'k', now(), now())")
                .param("t", person.tenantId())
                .param("u", person.userId())
                .param("m", SyntheticIds.mint())
                .update();

        assertThat(catchThrowable(() -> rollback(url, changesetsFromThisMigrationOn())))
                .hasStackTraceContaining("real_conditional_order is not empty");
        assertThat(tableExists(owner, "real_conditional_order")).isTrue();
        assertThat(owner.sql("select count(*) from pg_trigger where tgname = 'real_conditional_order_guard'")
                        .query(Long.class)
                        .single())
                .as("nothing of the table was rolled back")
                .isEqualTo(1L);
    }

    private static int changesets() throws java.io.IOException {
        return (int) Pattern.compile("(?m)^--changeset ")
                .matcher(Files.readString(MIGRATION, StandardCharsets.UTF_8))
                .results()
                .count();
    }

    /**
     * The changesets of 0022 and of every later migration of the master changelog, so the rollback reaches 0022 whatever
     * came after it (as RealOpsMigrationIT and AnonymizationMigrationIT do). A count of 0022 alone rolled back only the
     * later migrations once 0023 existed.
     */
    private static int changesetsFromThisMigrationOn() throws java.io.IOException {
        Path changelog = Path.of("src", "main", "resources", "db", "changelog");
        String master = Files.readString(changelog.resolve("db.changelog-master.yaml"), StandardCharsets.UTF_8);
        java.util.regex.Matcher includes =
                Pattern.compile("file:\\s*db/changelog/changes/(\\S+\\.sql)").matcher(master);
        int count = 0;
        boolean counting = false;
        while (includes.find()) {
            counting =
                    counting || includes.group(1).equals(MIGRATION.getFileName().toString());
            if (counting) {
                count += (int) Pattern.compile("(?m)^--changeset ")
                        .matcher(Files.readString(
                                changelog.resolve("changes").resolve(includes.group(1)), StandardCharsets.UTF_8))
                        .results()
                        .count();
            }
        }
        if (!counting) {
            throw new IllegalStateException(MIGRATION.getFileName() + " is not in the master changelog");
        }
        return count;
    }

    private static boolean tableExists(JdbcClient login, String table) {
        return login.sql("select count(*) from information_schema.tables where table_name = :t")
                        .param("t", table)
                        .query(Long.class)
                        .single()
                > 0;
    }

    private static boolean columnExists(JdbcClient login, String table, String column) {
        return login.sql("select count(*) from information_schema.columns where table_name = :t and column_name = :c")
                        .param("t", table)
                        .param("c", column)
                        .query(Long.class)
                        .single()
                > 0;
    }

    @SuppressWarnings("deprecation")
    private static void rollback(String url, int changesets) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, OWNER.user(), OWNER.password())) {
            Database database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            Liquibase liquibase =
                    new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), database);
            liquibase.rollback(changesets, "");
        }
    }
}
