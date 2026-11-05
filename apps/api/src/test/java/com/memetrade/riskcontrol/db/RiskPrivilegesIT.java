package com.memetrade.riskcontrol.db;

import static com.memetrade.testsupport.LedgerRows.sqlState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.memetrade.identity.api.Principal;
import com.memetrade.testsupport.AbstractU6IT;
import com.memetrade.testsupport.RiskRows;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Passo 4 of U6 (written before the migration 0009): the privilege matrix of infrastructure-specification.md, one row
 * per test, with what each login may and may not do on the four tables of U6, plus the triggers, checks, unique key and
 * foreign key (BR4.3 as amended by the NFR Design, BR2.8; NFR4.2, NFR4.3, NFR4.4, NFR5.3).
 */
class RiskPrivilegesIT extends AbstractU6IT {

    private static final String DENIED = "42501";
    private static final String CHECK = "23514";
    private static final String UNIQUE = "23505";
    private static final String FOREIGN_KEY = "23503";

    private JdbcClient app;
    private JdbcClient retention;
    private RiskRows asApp;
    private Principal person;
    private UUID bot;

    @BeforeEach
    void seed() {
        app = appRole();
        retention = retentionRole();
        asApp = new RiskRows(app);
        person = newPerson();
        bot = UUID.randomUUID();
    }

    // --- application login --------------------------------------------------------------------------------------

    @Test
    void theApplicationInsertsAndReadsEvaluationsButNeverUpdatesDeletesOrTruncatesThem() {
        UUID evaluation = asApp.evaluation(person, bot, "APPROVED", "40 days");

        assertThat(count(app, "select count(*) from order_evaluation where evaluation_id = '" + evaluation + "'"))
                .isEqualTo(1);
        assertThat(state(
                        app,
                        "update order_evaluation set limits_version = 2 where evaluation_id = '" + evaluation + "'"))
                .isEqualTo(DENIED);
        assertThat(state(app, "delete from order_evaluation where evaluation_id = '" + evaluation + "'"))
                .isEqualTo(DENIED);
        assertThat(state(app, "truncate order_evaluation cascade")).isEqualTo(DENIED);
    }

    @Test
    void theApplicationInsertsAndReadsRejectionsButNeverUpdatesOrDeletesThem() {
        UUID evaluation = asApp.evaluation(person, bot, "REJECTED", "1 minute");
        UUID rejection = asApp.rejection(person, bot, evaluation, "ORDER_SIZE");

        assertThat(count(app, "select count(*) from order_rejection where rejection_id = '" + rejection + "'"))
                .isEqualTo(1);
        assertThat(state(app, "update order_rejection set limits_version = 2 where rejection_id = '" + rejection + "'"))
                .isEqualTo(DENIED);
        assertThat(state(app, "delete from order_rejection where rejection_id = '" + rejection + "'"))
                .isEqualTo(DENIED);
        assertThat(state(app, "truncate order_rejection cascade")).isEqualTo(DENIED);
    }

    @Test
    void theApplicationInsertsAndReadsDetailsButNeverUpdatesOrDeletesThem() {
        UUID evaluation = asApp.evaluation(person, bot, "REJECTED", "1 minute");
        UUID rejection = asApp.rejection(person, bot, evaluation, "ORDER_SIZE");
        asApp.detail(person, rejection, "ORDER_SIZE", "PAPER");

        assertThat(count(app, "select count(*) from rejection_reason_detail where rejection_id = '" + rejection + "'"))
                .isEqualTo(1);
        assertThat(state(
                        app,
                        "update rejection_reason_detail set measured = '1' where rejection_id = '" + rejection + "'"))
                .isEqualTo(DENIED);
        assertThat(state(app, "delete from rejection_reason_detail where rejection_id = '" + rejection + "'"))
                .isEqualTo(DENIED);
        assertThat(state(app, "truncate rejection_reason_detail")).isEqualTo(DENIED);
    }

    @Test
    void theApplicationOnlyReadsThePurgeRunLog() {
        UUID run = new RiskRows(retention).purgeRun("1 hour", "SUCCEEDED");

        assertThat(count(app, "select count(*) from risk_purge_run where run_id = '" + run + "'"))
                .isEqualTo(1);
        assertThat(sqlState(catchThrowable(() -> asApp.purgeRun("1 hour", "SUCCEEDED"))))
                .isEqualTo(DENIED);
        assertThat(state(app, "update risk_purge_run set batches = 9 where run_id = '" + run + "'"))
                .isEqualTo(DENIED);
        assertThat(state(app, "delete from risk_purge_run where run_id = '" + run + "'"))
                .isEqualTo(DENIED);
    }

    @Test
    void theApplicationIsNotAMemberOfRiskRetentionAndCannotTakeIt() {
        assertThat(app.sql("select pg_has_role(current_user, 'risk_retention', 'MEMBER')")
                        .query(Boolean.class)
                        .single())
                .isFalse();
        assertThat(state(app, "set role risk_retention")).isEqualTo(DENIED);
    }

    // --- retention login (member of risk_retention) -------------------------------------------------------------

    @Test
    void theRetentionLoginDeletesOnlyApprovedEvaluationsOlderThan30DaysNotCitedByARejection() {
        UUID old = asApp.evaluation(person, bot, "APPROVED", "31 days");
        UUID recent = asApp.evaluation(person, bot, "APPROVED", "29 days");
        UUID oldRejected = asApp.evaluation(person, bot, "REJECTED", "40 days");
        UUID oldCited = asApp.evaluation(person, bot, "APPROVED", "40 days");
        asApp.rejection(person, bot, oldCited, "DUPLICATE");

        assertThat(retention
                        .sql("delete from order_evaluation where evaluation_id = :id")
                        .param("id", old)
                        .update())
                .isEqualTo(1);
        assertThat(state(retention, "delete from order_evaluation where evaluation_id = '" + recent + "'"))
                .isEqualTo(DENIED);
        assertThat(state(retention, "delete from order_evaluation where evaluation_id = '" + oldRejected + "'"))
                .isEqualTo(DENIED);
        assertThat(state(retention, "delete from order_evaluation where evaluation_id = '" + oldCited + "'"))
                .isEqualTo(FOREIGN_KEY);
        assertThat(count(
                        app,
                        "select count(*) from order_evaluation where evaluation_id in ('" + recent + "','" + oldRejected
                                + "','" + oldCited + "')"))
                .isEqualTo(3);
    }

    @Test
    void theRetentionLoginNeverInsertsOrUpdatesEvaluationsAndReadsOnlyTheEvaluationIdOfRejections() {
        UUID evaluation = asApp.evaluation(person, bot, "REJECTED", "1 minute");
        UUID rejection = asApp.rejection(person, bot, evaluation, "SLIPPAGE");
        asApp.detail(person, rejection, "SLIPPAGE", "PAPER");

        assertThat(sqlState(
                        catchThrowable(() -> new RiskRows(retention).evaluation(person, bot, "APPROVED", "40 days"))))
                .isEqualTo(DENIED);
        assertThat(state(
                        retention,
                        "update order_evaluation set limits_version = 2 where evaluation_id = '" + evaluation + "'"))
                .isEqualTo(DENIED);
        assertThat(count(retention, "select count(*) from order_rejection where evaluation_id = '" + evaluation + "'"))
                .isEqualTo(1);
        assertThat(state(retention, "select reasons from order_rejection")).isEqualTo(DENIED);
        assertThat(state(retention, "delete from order_rejection")).isEqualTo(DENIED);
        assertThat(state(retention, "select count(*) from rejection_reason_detail"))
                .isEqualTo(DENIED);
        assertThat(state(retention, "delete from rejection_reason_detail")).isEqualTo(DENIED);
    }

    @Test
    void theRetentionLoginWritesThePurgeRunLogAndDeletesOnlyRowsOlderThan90Days() {
        RiskRows asRetention = new RiskRows(retention);
        UUID recent = asRetention.purgeRun("89 days", "SUCCEEDED");
        UUID old = asRetention.purgeRun("91 days", "FAILED");

        assertThat(state(retention, "update risk_purge_run set batches = 9 where run_id = '" + recent + "'"))
                .isEqualTo(DENIED);
        assertThat(state(retention, "delete from risk_purge_run where run_id = '" + recent + "'"))
                .isEqualTo(DENIED);
        assertThat(retention
                        .sql("delete from risk_purge_run where run_id = :id")
                        .param("id", old)
                        .update())
                .isEqualTo(1);
        assertThat(sqlState(catchThrowable(() -> asRetention.purgeRun("1 hour", "RUNNING"))))
                .isEqualTo(CHECK);
    }

    // --- every login, including the schema owner ----------------------------------------------------------------

    @Test
    void updateIsRefusedOnTheFourTablesEvenForTheOwnerAndDeleteOfRecordsToo() {
        UUID evaluation = asApp.evaluation(person, bot, "APPROVED", "40 days");
        UUID rejection = asApp.rejection(person, bot, evaluation, "DUPLICATE");
        asApp.detail(person, rejection, "DUPLICATE", "PAPER");
        UUID run = new RiskRows(retention).purgeRun("100 days", "SUCCEEDED");
        JdbcClient owner = ownerRole();

        assertThat(state(
                        owner,
                        "update order_evaluation set limits_version = 2 where evaluation_id = '" + evaluation + "'"))
                .isEqualTo(DENIED);
        assertThat(state(
                        owner,
                        "update order_rejection set limits_version = 2 where rejection_id = '" + rejection + "'"))
                .isEqualTo(DENIED);
        assertThat(state(
                        owner,
                        "update rejection_reason_detail set unit = 'USDC' where rejection_id = '" + rejection + "'"))
                .isEqualTo(DENIED);
        assertThat(state(owner, "update risk_purge_run set batches = 1 where run_id = '" + run + "'"))
                .isEqualTo(DENIED);
        assertThat(state(owner, "delete from order_rejection where rejection_id = '" + rejection + "'"))
                .isEqualTo(DENIED);
        assertThat(state(owner, "delete from rejection_reason_detail where rejection_id = '" + rejection + "'"))
                .isEqualTo(DENIED);
        // The owner is not a member of risk_retention: not even an old approved evaluation.
        UUID uncited = asApp.evaluation(person, bot, "APPROVED", "40 days");
        assertThat(state(owner, "delete from order_evaluation where evaluation_id = '" + uncited + "'"))
                .isEqualTo(DENIED);
        for (String table :
                List.of("order_evaluation", "order_rejection", "rejection_reason_detail", "risk_purge_run")) {
            assertThat(state(owner, "truncate " + table + " cascade")).as(table).isEqualTo(DENIED);
        }
    }

    @Test
    void everyRowIsPaperOnly() {
        assertThat(sqlState(catchThrowable(
                        () -> asApp.evaluation(person, bot, UUID.randomUUID(), "APPROVED", "1 minute", "REAL"))))
                .isEqualTo(CHECK);
        UUID evaluation = asApp.evaluation(person, bot, "REJECTED", "1 minute");
        assertThat(sqlState(catchThrowable(() -> asApp.rejection(person, bot, evaluation, "SLIPPAGE", "REAL"))))
                .isEqualTo(CHECK);
        UUID rejection = asApp.rejection(person, bot, evaluation, "SLIPPAGE");
        assertThat(sqlState(catchThrowable(() -> asApp.detail(person, rejection, "SLIPPAGE", "REAL"))))
                .isEqualTo(CHECK);
    }

    @Test
    void reasonsAndUnitsAreClosedListsAndARejectionHasAtLeastOneReason() {
        UUID evaluation = asApp.evaluation(person, bot, "REJECTED", "1 minute");

        assertThat(sqlState(catchThrowable(() -> asApp.rejection(person, bot, evaluation, ""))))
                .isEqualTo(CHECK);
        assertThat(sqlState(catchThrowable(() -> asApp.rejection(person, bot, evaluation, "ORDER_SIZE,NOPE"))))
                .isEqualTo(CHECK);
        UUID rejection = asApp.rejection(person, bot, evaluation, "ORDER_SIZE,DAILY_LOSS");
        assertThat(sqlState(catchThrowable(() -> asApp.detail(person, rejection, "NOPE", "PAPER"))))
                .isEqualTo(CHECK);
        asApp.detail(person, rejection, "ORDER_SIZE", "PAPER");
        assertThat(sqlState(catchThrowable(() -> asApp.detail(person, rejection, "ORDER_SIZE", "PAPER"))))
                .isEqualTo(UNIQUE);
        assertThat(sqlState(catchThrowable(
                        () -> asApp.evaluation(person, bot, UUID.randomUUID(), "MAYBE", "1 minute", "PAPER"))))
                .isEqualTo(CHECK);
    }

    @Test
    void oneEvaluationPerDecisionAndTheConflictingInsertDoesNothingWithoutAborting() {
        UUID decision = UUID.randomUUID();
        asApp.evaluation(person, bot, decision, "APPROVED", "1 minute", "PAPER");

        assertThat(sqlState(
                        catchThrowable(() -> asApp.evaluation(person, bot, decision, "REJECTED", "1 minute", "PAPER"))))
                .isEqualTo(UNIQUE);
        int inserted = app.sql("""
                        insert into order_evaluation (evaluation_id, tenant_id, owner_user_id, mode, bot_id, decision_id,
                            side, chain, token_mint, requested_quantity, limits_version, outcome, evaluated_at)
                        values (gen_random_uuid(), :tenant, :owner, 'PAPER', :bot, :decision, 'BUY', 'solana', 'm', 1, 1,
                            'APPROVED', now())
                        on conflict (tenant_id, mode, bot_id, decision_id) do nothing
                        """)
                .param("tenant", person.tenantId())
                .param("owner", person.userId())
                .param("bot", bot)
                .param("decision", decision)
                .update();
        assertThat(inserted).isZero();
    }

    @Test
    void theForeignKeyOfTheRejectionHasNoOnDeleteAndTheScalesAreExactDecimals() {
        String action = jdbc.sql("""
                        select confdeltype from pg_constraint
                        where conrelid = 'order_rejection'::regclass and contype = 'f'
                          and confrelid = 'order_evaluation'::regclass
                        """).query(String.class).single();
        assertThat(action).isEqualTo("a");

        Map<String, String> types =
                jdbc.sql("""
                        select table_name || '.' || column_name as col,
                               data_type || coalesce('(' || numeric_precision || ',' || numeric_scale || ')', '') as type
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name in ('order_evaluation', 'order_rejection', 'rejection_reason_detail',
                                             'risk_purge_run')
                        """).query((rs, n) -> Map.entry(rs.getString("col"), rs.getString("type"))).list().stream()
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(types.get("order_evaluation.requested_quantity")).isEqualTo("numeric(38,18)");
        assertThat(types.get("order_rejection.requested_quantity")).isEqualTo("numeric(38,18)");
        assertThat(types.get("order_evaluation.evaluated_at")).isEqualTo("timestamp with time zone");
        assertThat(types.get("order_rejection.market")).isEqualTo("jsonb");
        assertThat(types.values()).noneMatch(t -> t.startsWith("real") || t.startsWith("double"));
    }

    @Test
    void thePurgeAndPageIndexesExist() {
        List<String> indexes = jdbc.sql("""
                        select indexdef from pg_indexes
                        where tablename in ('order_evaluation', 'order_rejection')
                        """).query(String.class).list();

        assertThat(indexes)
                .anyMatch(def -> def.contains("(evaluated_at)") && def.contains("WHERE") && def.contains("APPROVED"))
                .anyMatch(def ->
                        def.contains("(tenant_id, owner_user_id, mode, bot_id, evaluated_at DESC, rejection_id DESC)"))
                .anyMatch(def -> def.contains("order_rejection") && def.contains("(evaluation_id)"))
                .anyMatch(def -> def.contains("UNIQUE") && def.contains("(tenant_id, mode, bot_id, decision_id)"));
    }

    /** The SQLSTATE of a statement that must fail ({@code none} when it unexpectedly succeeds). */
    private static String state(JdbcClient login, String sql) {
        boolean read = sql.startsWith("select");
        return sqlState(catchThrowable(() -> {
            if (read) {
                login.sql(sql).query().listOfRows();
            } else {
                login.sql(sql).update();
            }
        }));
    }

    private static long count(JdbcClient login, String sql) {
        return login.sql(sql).query(Long.class).single();
    }
}
