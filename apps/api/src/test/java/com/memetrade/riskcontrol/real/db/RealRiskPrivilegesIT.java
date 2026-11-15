package com.memetrade.riskcontrol.real.db;

import static com.memetrade.testsupport.LedgerRows.sqlState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Step 5 of the U3, written before the migration 0018 (infrastructure-specification.md "Privilégios" and "Gatilhos";
 * U3-NFR3.6, U3-NFR3.7, U3-NFR8.6): what the application login, the retention login and the owner may and may not do
 * on the five tables of the REAL barrier, the age triggers of 13 months, the transition trigger of the reservation, the
 * refusal of UPDATE and TRUNCATE, and the checks. Statements are run as each login on its own connection.
 */
class RealRiskPrivilegesIT extends AbstractPostgresIT {

    private static final String DENIED = "42501";
    private static final String CHECK = "23514";
    private static final String UNIQUE = "23505";

    private JdbcClient app;
    private JdbcClient retention;
    private JdbcClient owner;
    private UUID tenant;
    private UUID user;

    @BeforeEach
    void seed() {
        app = connect(APP);
        retention = connect(RETENTION);
        owner = connect(OWNER);
        TestAccounts.Seeded person = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        tenant = person.tenantId();
        user = person.userId();
    }

    private static String state(JdbcClient login, String sql) {
        return sqlState(catchThrowable(() -> login.sql(sql).update()));
    }

    private static long count(JdbcClient login, String sql) {
        return login.sql(sql).query(Long.class).single();
    }

    /** An evaluation evaluated {@code age} before the database now(), as the application writes it. */
    private UUID evaluation(String outcome, String age) {
        UUID id = UUID.randomUUID();
        String rejected = "REJECTED".equals(outcome) ? "'REAL_LIMIT_EXCEEDED', 'ORDER_SIZE'" : "null, null";
        app.sql(
                        "insert into real_risk_evaluation (evaluation_id, tenant_id, user_id, bot_id, decision_id, origin, side,"
                                + " chain, mint, input_lamports, outcome, code, detail, figures, evaluated_at, evaluated_day)"
                                + " values (:id, :tenant, :user, :bot, :decision, 'BOT', 'BUY', 'solana', :mint, 1000, :outcome, "
                                + rejected + ", '{}', now() - cast(:age as interval),"
                                + " cast((now() - cast(:age as interval)) at time zone 'UTC' as date))")
                .param("id", id)
                .param("tenant", tenant)
                .param("user", user)
                .param("bot", UUID.randomUUID())
                .param("decision", UUID.randomUUID())
                .param("mint", SyntheticIds.mint())
                .param("outcome", outcome)
                .param("age", age)
                .update();
        return id;
    }

    private UUID reservation(String state, String age) {
        UUID id = UUID.randomUUID();
        app.sql(
                        "insert into real_risk_reservation (approval_id, tenant_id, user_id, bot_wallet_id, bot_id, decision_id,"
                                + " mint, input_lamports, state, created_at, expires_at, closed_at) values (:id, :tenant, :user,"
                                + " :wallet, :bot, :decision, :mint, 1000, :state, now() - cast(:age as interval),"
                                + " now() - cast(:age as interval) + interval '5 minutes',"
                                + " case when :state = 'OPEN' then null else now() - cast(:age as interval) end)")
                .param("id", id)
                .param("tenant", tenant)
                .param("user", user)
                .param("wallet", UUID.randomUUID())
                .param("bot", UUID.randomUUID())
                .param("decision", UUID.randomUUID())
                .param("mint", SyntheticIds.mint())
                .param("state", state)
                .param("age", age)
                .update();
        return id;
    }

    private UUID day(String age) {
        UUID wallet = UUID.randomUUID();
        app.sql("insert into real_risk_day (tenant_id, bot_wallet_id, day, start_value_lamports, start_cap_lamports,"
                        + " taken_at, taken_by) values (:tenant, :wallet, cast((now() - cast(:age as interval))"
                        + " at time zone 'UTC' as date), 10, 10, now() - cast(:age as interval), 'DAY_JOB')")
                .param("tenant", tenant)
                .param("wallet", wallet)
                .param("age", age)
                .update();
        return wallet;
    }

    @Test
    void theApplicationInsertsAndReadsEvaluationsAndNeverChangesThem() {
        UUID id = evaluation("APPROVED", "1 minute");

        assertThat(count(app, "select count(*) from real_risk_evaluation where evaluation_id = '" + id + "'"))
                .isEqualTo(1);
        assertThat(state(
                        app, "update real_risk_evaluation set outcome = 'REJECTED' where evaluation_id = '" + id + "'"))
                .isEqualTo(DENIED);
        assertThat(state(app, "delete from real_risk_evaluation where evaluation_id = '" + id + "'"))
                .isEqualTo(DENIED);
        assertThat(state(app, "truncate real_risk_evaluation")).isEqualTo(DENIED);
    }

    @Test
    void aReservationMovesOnlyOnceFromOpenToAClosedStateAndIsNeverDeletedByTheApplication() {
        UUID id = reservation("OPEN", "1 minute");

        assertThat(state(app, "update real_risk_reservation set input_lamports = 1 where approval_id = '" + id + "'"))
                .isEqualTo(DENIED);
        assertThat(state(
                        app, "update real_risk_reservation set state = 'HANDED_OVER' where approval_id = '" + id + "'"))
                .as("a closed state needs closed_at")
                .isIn(DENIED, CHECK);
        app.sql("update real_risk_reservation set state = 'HANDED_OVER', closed_at = now() where approval_id = '" + id
                        + "'")
                .update();
        assertThat(state(
                        app,
                        "update real_risk_reservation set state = 'EXPIRED', closed_at = now() where approval_id = '"
                                + id + "'"))
                .isEqualTo(DENIED);
        assertThat(state(app, "delete from real_risk_reservation where approval_id = '" + id + "'"))
                .isEqualTo(DENIED);
        assertThat(state(app, "truncate real_risk_reservation")).isEqualTo(DENIED);
    }

    @Test
    void thePhotoOfTheDayIsUniquePerWalletAndDayAndNeverChanges() {
        UUID wallet = day("1 minute");

        assertThat(state(
                        app,
                        "insert into real_risk_day (tenant_id, bot_wallet_id, day, start_value_lamports,"
                                + " start_cap_lamports, taken_at, taken_by) select tenant_id, bot_wallet_id, day, 1, 1,"
                                + " now(), 'FIRST_EVALUATION' from real_risk_day where bot_wallet_id = '" + wallet
                                + "'"))
                .isEqualTo(UNIQUE);
        assertThat(state(
                        app,
                        "update real_risk_day set start_value_lamports = 1 where bot_wallet_id = '" + wallet + "'"))
                .isEqualTo(DENIED);
        assertThat(state(app, "delete from real_risk_day where bot_wallet_id = '" + wallet + "'"))
                .isEqualTo(DENIED);
    }

    @Test
    void theApplicationBlocksAndUnblocksATokenButNeverRewritesTheReason() {
        String mint = SyntheticIds.mint();
        String insert =
                "insert into real_blocked_token (chain, mint, reason, blocked_by, blocked_at) values ('solana', '"
                        + mint + "', 'rug', '" + user + "', now())";
        app.sql(insert).update();

        assertThat(state(app, insert)).isEqualTo(UNIQUE);
        assertThat(state(app, "update real_blocked_token set reason = 'x' where mint = '" + mint + "'"))
                .isEqualTo(DENIED);
        assertThat(app.sql("delete from real_blocked_token where mint = '" + mint + "'")
                        .update())
                .isEqualTo(1);
        assertThat(state(app, "truncate real_blocked_token")).isEqualTo(DENIED);
    }

    @Test
    void theApplicationHasNoAccessToThePurgeRunLog() {
        assertThat(state(app, "select count(*) from real_risk_purge_run")).isEqualTo(DENIED);
        assertThat(
                        state(
                                app,
                                "insert into real_risk_purge_run (run_id, started_at, finished_at, cutoff, deleted_count,"
                                        + " batches, result) values (gen_random_uuid(), now(), now(), now(), 0, 0, 'SUCCEEDED')"))
                .isEqualTo(DENIED);
    }

    @Test
    void theRetentionLoginDeletesOnlyEvaluationsOlderThan13Months() {
        UUID old = evaluation("REJECTED", "14 months");
        UUID recent = evaluation("APPROVED", "12 months");

        assertThat(retention
                        .sql("delete from real_risk_evaluation where evaluation_id = '" + old + "'")
                        .update())
                .isEqualTo(1);
        assertThat(state(retention, "delete from real_risk_evaluation where evaluation_id = '" + recent + "'"))
                .isEqualTo(DENIED);
        assertThat(state(
                        retention,
                        "update real_risk_evaluation set outcome = 'APPROVED' where evaluation_id = '" + recent + "'"))
                .isEqualTo(DENIED);
    }

    @Test
    void theRetentionLoginDeletesOldReservationsAndPhotosButNeverRecentOnes() {
        UUID oldReservation = reservation("EXPIRED", "14 months");
        UUID recentReservation = reservation("HANDED_OVER", "1 day");
        UUID oldDay = day("14 months");
        UUID recentDay = day("2 days");

        assertThat(retention
                        .sql("delete from real_risk_reservation where approval_id = '" + oldReservation + "'")
                        .update())
                .isEqualTo(1);
        assertThat(state(
                        retention, "delete from real_risk_reservation where approval_id = '" + recentReservation + "'"))
                .isEqualTo(DENIED);
        assertThat(retention
                        .sql("delete from real_risk_day where bot_wallet_id = '" + oldDay + "'")
                        .update())
                .isEqualTo(1);
        assertThat(state(retention, "delete from real_risk_day where bot_wallet_id = '" + recentDay + "'"))
                .isEqualTo(DENIED);
        assertThat(state(retention, "select count(*) from real_blocked_token")).isEqualTo(DENIED);
    }

    @Test
    void theRetentionLoginWritesThePurgeLogAndDeletesOnlyRowsOlderThan90Days() {
        String insert = "insert into real_risk_purge_run (run_id, started_at, finished_at, cutoff, deleted_count,"
                + " batches, result) values ('%s', now() - interval '%s', now(), now(), 0, 0, 'SUCCEEDED')";
        UUID old = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        retention.sql(insert.formatted(old, "91 days")).update();
        retention.sql(insert.formatted(recent, "1 day")).update();

        assertThat(retention
                        .sql("delete from real_risk_purge_run where run_id = '" + old + "'")
                        .update())
                .isEqualTo(1);
        assertThat(state(retention, "delete from real_risk_purge_run where run_id = '" + recent + "'"))
                .isEqualTo(DENIED);
        assertThat(state(retention, "update real_risk_purge_run set batches = 9 where run_id = '" + recent + "'"))
                .isEqualTo(DENIED);
    }

    @Test
    void evenTheOwnerNeverTruncatesNorRewritesTheRecords() {
        UUID id = evaluation("APPROVED", "1 minute");

        for (String table : new String[] {
            "real_risk_evaluation",
            "real_risk_reservation",
            "real_risk_day",
            "real_blocked_token",
            "real_risk_purge_run"
        }) {
            assertThat(state(owner, "truncate " + table)).as(table).isEqualTo(DENIED);
        }
        assertThat(state(owner, "update real_risk_evaluation set side = 'SELL' where evaluation_id = '" + id + "'"))
                .isEqualTo(DENIED);
        assertThat(state(owner, "delete from real_risk_evaluation where evaluation_id = '" + id + "'"))
                .isEqualTo(DENIED);
    }

    @Test
    void theChecksRefuseInconsistentRows() {
        assertThat(sqlState(catchThrowable(() -> evaluation("MAYBE", "1 minute"))))
                .isEqualTo(CHECK);
        assertThat(state(
                        app,
                        "insert into real_blocked_token (chain, mint, reason, blocked_by, blocked_at) values ('solana',"
                                + " '" + SyntheticIds.mint() + "', '" + "x".repeat(501) + "', '" + user + "', now())"))
                .as("more than 500 characters: the column refuses it (value too long)")
                .isEqualTo("22001");
        assertThat(state(
                        app,
                        "insert into real_blocked_token (chain, mint, reason, blocked_by, blocked_at) values ('solana',"
                                + " '" + SyntheticIds.mint() + "', '   ', '" + user + "', now())"))
                .isEqualTo(CHECK);
        assertThat(sqlState(catchThrowable(() -> reservation("LOST", "1 minute"))))
                .isEqualTo(CHECK);
        assertThat(jdbc.sql("select column_default from information_schema.columns where table_name ="
                                + " 'real_preferences' and column_name = 'paused_since'")
                        .query(String.class)
                        .single())
                .contains("'{}'::jsonb");
    }
}
