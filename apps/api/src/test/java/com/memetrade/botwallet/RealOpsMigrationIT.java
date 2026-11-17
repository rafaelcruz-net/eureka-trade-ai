package com.memetrade.botwallet;

import static com.memetrade.testsupport.LedgerRows.sqlState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.regex.Matcher;
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
 * Step 3 of the U5, written BEFORE the migration 0021 (infrastructure-specification.md "Banco: migração 0021",
 * security-design.md "Redação da trilha", U5-NFR1.3, U5-NFR3.8, U5-NFR3.9, U5-NFR8.6): the redaction of the trail is the
 * one exception of the append-only trigger, only through the SECURITY DEFINER function; the matrix of privileges and the
 * age triggers of the five new tables; the compatibility trigger of {@code revoked_at}; the precondition on duplicate
 * releases; the new CHECK of the withdrawal; the widened enums; and a rollback per changeset.
 */
@Tag("u5-real-operations")
class RealOpsMigrationIT extends AbstractPostgresIT {

    private static final String DENIED = "42501";
    private static final String UNIQUE = "23505";
    private static final String CHECK = "23514";
    private static final Path MIGRATION =
            Path.of("src", "main", "resources", "db", "changelog", "changes", "0021-real-operations.sql");

    private JdbcClient app;
    private JdbcClient owner;
    private JdbcClient retention;
    private UUID tenant;
    private UUID user;

    @BeforeEach
    void seed() {
        app = connect(APP);
        owner = connect(OWNER);
        retention = connect(RETENTION);
        TestAccounts.Seeded person =
                TestAccounts.betaUser(jdbc, TestWallet.generate().address());
        tenant = person.tenantId();
        user = person.userId();
    }

    private static String state(JdbcClient login, String sql) {
        return sqlState(catchThrowable(() -> login.sql(sql).query().singleRow()));
    }

    private static String update(JdbcClient login, String sql) {
        return sqlState(catchThrowable(() -> login.sql(sql).update()));
    }

    /** A bot wallet of a new person (one ACTIVE wallet per person). */
    private UUID wallet(String address) {
        UUID owner =
                TestAccounts.betaUser(jdbc, TestWallet.generate().address()).userId();
        UUID wallet = UUID.randomUUID();
        app.sql("""
                        insert into bot_wallet_account (bot_wallet_id, tenant_id, user_id, public_address,
                          main_wallet_address, network, created_at)
                        values (:id, :t, :u, :address, :main, 'LOCALNET', now())""")
                .param("id", wallet)
                .param("t", tenant)
                .param("u", owner)
                .param("address", address)
                .param("main", TestWallet.generate().address())
                .update();
        return wallet;
    }

    private UUID auditEvent(String botWalletAddress) {
        UUID event = UUID.randomUUID();
        app.sql("""
                        insert into audit_event (event_id, tenant_id, actor, actor_type, action, target_ref,
                          correlation_id, occurred_at, recorded_at, details, bot_wallet_address)
                        values (:id, :t, 'system', 'SYSTEM', 'REAL_DEPOSIT_RECORDED', 'bot-wallet:x', 'corr', now(),
                          now(), '{"lamports":"1"}'::jsonb, :address)""")
                .param("id", event)
                .param("t", tenant)
                .param("address", botWalletAddress)
                .update();
        return event;
    }

    private UUID outbox(String botWalletAddress) {
        UUID event = UUID.randomUUID();
        app.sql("""
                        insert into audit_outbox (event_id, payload, created_at, delivered_at)
                        values (:id, cast(:payload as jsonb), now(), now())""")
                .param("id", event)
                // A complete payload, already delivered: the relay of the other tests (shared database) never picks it
                // up, and if it did, it would decode it.
                .param(
                        "payload",
                        "{\"eventId\":\"" + event + "\",\"tenantId\":null,\"actor\":\"system\","
                                + "\"actorType\":\"SYSTEM\",\"action\":\"REAL_DEPOSIT_RECORDED\","
                                + "\"targetRef\":\"bot-wallet:x\",\"correlationId\":\"corr\","
                                + "\"occurredAt\":\"2026-11-16T00:00:00Z\",\"details\":{},"
                                + "\"real\":{\"txSignature\":null,\"mint\":null,\"botWalletAddress\":\""
                                + botWalletAddress + "\"}}")
                .update();
        return event;
    }

    // ----- the trail (security-design.md "Redação da trilha") -----

    @Test
    void theRedactionFunctionClearsTheBotWalletOfTheTrailAndOfTheOutboxAndRecordsItsCount() {
        String address = TestWallet.generate().address();
        UUID event = auditEvent(address);
        UUID pending = outbox(address);
        UUID other = auditEvent(TestWallet.generate().address());

        Integer count = app.sql("select redact_real_wallet_audit(:address)")
                .param("address", address)
                .query(Integer.class)
                .single();

        assertThat(count).isEqualTo(2);
        assertThat(jdbc.sql("select bot_wallet_address is null from audit_event where event_id = :id")
                        .param("id", event)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(jdbc.sql("select action from audit_event where event_id = :id")
                        .param("id", event)
                        .query(String.class)
                        .single())
                .isEqualTo("REAL_DEPOSIT_RECORDED");
        assertThat(jdbc.sql("select payload::text from audit_outbox where event_id = :id")
                        .param("id", pending)
                        .query(String.class)
                        .single())
                .doesNotContain(address)
                .contains("\"txSignature\"");
        assertThat(jdbc.sql("select bot_wallet_address from audit_event where event_id = :id")
                        .param("id", other)
                        .query(String.class)
                        .single())
                .isNotNull();
        assertThat(jdbc.sql("select details ->> 'eventRows' from audit_event where action = 'REAL_AUDIT_REDACTED'"
                                + " order by recorded_at desc limit 1")
                        .query(String.class)
                        .single())
                .isEqualTo("1");
    }

    @Test
    void anUpdateOfAnyOtherColumnIsRefusedEvenWithTheVariableOn() {
        String address = TestWallet.generate().address();
        UUID event = auditEvent(address);

        Throwable otherColumn = catchThrowable(() -> owner.sql("DO $$ BEGIN PERFORM set_config("
                        + "'memetrade.audit_redaction', 'on', true); UPDATE audit_event SET action = 'CHANGED' WHERE"
                        + " event_id = '" + event + "'; END $$")
                .update());
        Throwable both = catchThrowable(() -> owner.sql("DO $$ BEGIN PERFORM set_config("
                        + "'memetrade.audit_redaction', 'on', true); UPDATE audit_event SET bot_wallet_address = NULL,"
                        + " target_ref = 'x' WHERE event_id = '" + event + "'; END $$")
                .update());
        Throwable withoutVariable = catchThrowable(
                () -> owner.sql("update audit_event set bot_wallet_address = null where event_id = '" + event + "'")
                        .update());

        assertThat(otherColumn).hasStackTraceContaining("audit_event is append-only");
        assertThat(both).hasStackTraceContaining("audit_event is append-only");
        assertThat(withoutVariable).hasStackTraceContaining("audit_event is append-only");
        assertThat(jdbc.sql("select bot_wallet_address from audit_event where event_id = :id")
                        .param("id", event)
                        .query(String.class)
                        .single())
                .isEqualTo(address);
    }

    @Test
    void theApplicationHasNoUpdateOnTheTrailAndNobodyElseExecutesTheFunction() {
        UUID event = auditEvent(TestWallet.generate().address());

        assertThat(update(app, "update audit_event set bot_wallet_address = null where event_id = '" + event + "'"))
                .isEqualTo(DENIED);
        assertThat(jdbc.sql("select has_function_privilege('public', 'redact_real_wallet_audit(text)', 'EXECUTE')")
                        .query(Boolean.class)
                        .single())
                .isFalse();
        assertThat(state(
                        retention,
                        "select redact_real_wallet_audit('"
                                + TestWallet.generate().address() + "')"))
                .isEqualTo(DENIED);
        assertThat(jdbc.sql("""
                        select p.prosecdef and pg_get_userbyid(p.proowner) = :owner
                          and array_to_string(p.proconfig, ',') like '%search_path=%'
                        from pg_proc p where p.proname = 'redact_real_wallet_audit'""")
                        .param("owner", OWNER.user())
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void theRedactionRefusesSomethingThatIsNotAnAddress() {
        assertThat(state(app, "select redact_real_wallet_audit('%')")).isNotEqualTo("none");
    }

    @Test
    void theTrailHasTheTwoPartialIndexesOfTheRedaction() {
        assertThat(jdbc.sql("select count(*) from pg_indexes where indexname in"
                                + " ('audit_event_bot_wallet_idx', 'audit_outbox_bot_wallet_idx')")
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    // ----- real_release (BR1.2, BR1.4, the compatibility with the previous images) -----

    @Test
    void anOldImageThatSetsRevokedAtMakesTheReleaseRevokedAndARevokedOneNeverComesBack() {
        UUID release = UUID.randomUUID();
        app.sql("""
                        insert into real_release (release_id, user_id, network, requested_by, requested_at, effective_at)
                        values (:id, :u, 'DEVNET', :u, now(), now() + interval '24 hours')""").param("id", release).param("u", user).update();
        assertThat(releaseState(release)).isEqualTo("QUARANTINE");

        app.sql("update real_release set revoked_at = now() where release_id = :id")
                .param("id", release)
                .update();

        assertThat(releaseState(release)).isEqualTo("REVOKED");
        assertThat(update(app, "update real_release set state = 'ACTIVE' where release_id = '" + release + "'"))
                .isEqualTo(DENIED);
        assertThat(update(app, "delete from real_release where release_id = '" + release + "'"))
                .isEqualTo(DENIED);
    }

    @Test
    void onlyOneReleaseNotRevokedPerPersonAndNetwork() {
        String insert = "insert into real_release (release_id, user_id, network, requested_by, requested_at,"
                + " effective_at) values (gen_random_uuid(), '" + user + "', 'MAINNET', '" + user
                + "', now(), now() + interval '24 hours')";

        assertThat(update(app, insert)).isEqualTo("none");
        assertThat(update(app, insert)).isEqualTo(UNIQUE);
        assertThat(update(
                        app,
                        "update real_release set revoked_at = now() where user_id = '" + user
                                + "' and network = 'MAINNET'"))
                .isEqualTo("none");
        assertThat(update(app, insert)).isEqualTo("none");
    }

    private String releaseState(UUID release) {
        return jdbc.sql("select state from real_release where release_id = :id")
                .param("id", release)
                .query(String.class)
                .single();
    }

    // ----- withdrawal (BR6.2) -----

    @Test
    void aSignedWithdrawalNeedsItsTransactionUnlessItWasRedacted() {
        UUID wallet = wallet(TestWallet.generate().address());
        String insert = "insert into withdrawal (withdrawal_id, tenant_id, bot_wallet_id, kind, lamports, fee_lamports,"
                + " state, idempotency_key, signing_request_id, next_attempt_at, requested_at, updated_at,"
                + " redacted_at) values (gen_random_uuid(), '" + tenant + "', '" + wallet + "', 'TOTAL', 1000, 5000,"
                + " 'CONFIRMED', gen_random_uuid(), gen_random_uuid(), now(), now(), now(), %s)";

        assertThat(update(app, insert.formatted("null"))).isEqualTo(CHECK);
        assertThat(update(app, insert.formatted("now()"))).isEqualTo("none");
    }

    @Test
    void theAddressesBecomeNullableButAnActiveWalletKeepsBoth() {
        UUID wallet = wallet(TestWallet.generate().address());

        assertThat(update(
                        app,
                        "update bot_wallet_account set main_wallet_address = null where bot_wallet_id = '" + wallet
                                + "'"))
                .isEqualTo(CHECK);
        assertThat(update(
                        app,
                        "update bot_wallet_account set status = 'CLOSED', closed_at = now(),"
                                + " main_wallet_address = null, public_address = null where bot_wallet_id = '" + wallet
                                + "'"))
                .isEqualTo("none");
        assertThat(update(
                        app,
                        "update bot_wallet_account set public_address = 'not base58 0OIl' where" + " bot_wallet_id = '"
                                + wallet + "'"))
                .isEqualTo(CHECK);
        assertThat(jdbc.sql("select is_nullable from information_schema.columns where table_name ="
                                + " 'consent_challenge' and column_name = 'message'")
                        .query(String.class)
                        .single())
                .isEqualTo("YES");
    }

    // ----- the widened enums (BR2.5, BR7.1, the compensation of BR2.3) -----

    @Test
    void theNewReasonsDetailsModeAndCompensationAreAccepted() {
        UUID wallet = wallet(TestWallet.generate().address());
        app.sql("""
                        insert into real_preferences (bot_wallet_id, cap_lamports, fee_reserve_lamports,
                          max_withdrawal_fee_lamports, paused_reasons, updated_at)
                        values (:w, 1, 0, 0, array['RESTORE', 'RESTORE_CHECK_PENDING'], now())""").param("w", wallet).update();
        String evaluation = "insert into real_risk_evaluation (evaluation_id, tenant_id, user_id, bot_id, decision_id,"
                + " origin, side, chain, mint, input_lamports, outcome, code, detail, evaluated_at, evaluated_day)"
                + " values (gen_random_uuid(), '" + tenant + "', '" + user + "', gen_random_uuid(), gen_random_uuid(),"
                + " 'BOT', 'BUY', 'solana', '" + SyntheticIds.mint() + "', 1, 'REJECTED', 'REAL_PAUSED', '%s', now(),"
                + " (now() at time zone 'UTC')::date)";

        assertThat(update(app, evaluation.formatted("PAUSED_RESTORE"))).isEqualTo("none");
        assertThat(update(app, evaluation.formatted("PAUSED_RESTORE_CHECK_PENDING")))
                .isEqualTo("none");
        assertThat(update(app, evaluation.formatted("PAUSED_SOMETHING"))).isEqualTo(CHECK);
        assertThat(jdbc.sql("select pg_get_constraintdef(oid) from pg_constraint where conname = 'decision_mode_ck'")
                        .query(String.class)
                        .single())
                .contains("REAL")
                .contains("PAPER");
        String compensation = "insert into real_ledger_entry (entry_id, tenant_id, bot_wallet_id, entry_kind, lamports,"
                + " mint, token_base_units, reference, idempotency_key, created_at) values (gen_random_uuid(), '"
                + tenant + "', '" + wallet + "', 'COMPENSATION', %s, %s, %s, 'div:x', gen_random_uuid()::text, now())";
        assertThat(update(app, compensation.formatted("1000", "null", "null"))).isEqualTo("none");
        assertThat(update(app, compensation.formatted("0", "'" + SyntheticIds.mint() + "'", "-25")))
                .isEqualTo("none");
        assertThat(update(app, compensation.formatted("0", "null", "null"))).isEqualTo(CHECK);
        assertThat(update(app, compensation.formatted("5", "'" + SyntheticIds.mint() + "'", "7")))
                .isEqualTo(CHECK);
    }

    // ----- the new tables: privileges and age triggers (U5-NFR3.9, U5-NFR4.1) -----

    private UUID run(UUID wallet, String result, String age) {
        UUID run = UUID.randomUUID();
        app.sql("insert into real_reconciliation_run (run_id, tenant_id, bot_wallet_id, trigger, slot, chain_lamports,"
                        + " ledger_lamports, token_diffs, in_transit_orders, result, started_at, finished_at) values"
                        + " (:id, :t, :w, 'SCHEDULE', 10, 1, 1, '[]'::jsonb, 0, :result,"
                        + " now() - cast(:age as interval), now() - cast(:age as interval))")
                .param("id", run)
                .param("t", tenant)
                .param("w", wallet)
                .param("result", result)
                .param("age", age)
                .update();
        return run;
    }

    private UUID divergence(UUID wallet, UUID run, String state, String age) {
        UUID divergence = UUID.randomUUID();
        app.sql("insert into real_divergence (divergence_id, run_id, last_run_id, tenant_id, bot_wallet_id,"
                        + " lamports_diff, token_diffs, state, opened_at, updated_at, resolved_at) values (:id, :run,"
                        + " :run, :t, :w, 1000, '[]'::jsonb, :state, now() - cast(:age as interval), now() - cast(:age"
                        + " as interval), case when :state = 'RESOLVED' then now() - cast(:age as interval) end)")
                .param("id", divergence)
                .param("run", run)
                .param("t", tenant)
                .param("w", wallet)
                .param("state", state)
                .param("age", age)
                .update();
        return divergence;
    }

    @Test
    void aRunIsInsertOnlyAndOnlyTheRetentionDeletesAnOldCleanRun() {
        UUID wallet = wallet(TestWallet.generate().address());
        UUID young = run(wallet, "CLEAN", "1 day");
        UUID old = run(wallet, "CLEAN", "13 months 1 day");

        assertThat(update(
                        app, "update real_reconciliation_run set result = 'DIVERGENT' where run_id = '" + young + "'"))
                .isEqualTo(DENIED);
        assertThat(update(app, "delete from real_reconciliation_run where run_id = '" + old + "'"))
                .isEqualTo(DENIED);
        assertThat(update(retention, "delete from real_reconciliation_run where run_id = '" + young + "'"))
                .isEqualTo(DENIED);
        assertThat(update(retention, "delete from real_reconciliation_run where run_id = '" + old + "'"))
                .isEqualTo("none");
    }

    @Test
    void aRunOfADivergenceIsNeverDeletedBeforeItAndADivergenceLivesFiveYears() {
        UUID wallet = wallet(TestWallet.generate().address());
        UUID run = run(wallet, "DIVERGENT", "5 years 2 days");
        UUID divergence = divergence(wallet, run, "RESOLVED", "5 years 1 day");
        UUID young = divergence(wallet(TestWallet.generate().address()), run, "RESOLVED", "4 years");

        assertThat(update(retention, "delete from real_reconciliation_run where run_id = '" + run + "'"))
                .isNotEqualTo("none");
        assertThat(update(retention, "delete from real_divergence where divergence_id = '" + young + "'"))
                .isEqualTo(DENIED);
        assertThat(update(retention, "delete from real_divergence where divergence_id = '" + divergence + "'"))
                .isEqualTo("none");
        assertThat(update(app, "delete from real_divergence where divergence_id = '" + young + "'"))
                .isEqualTo(DENIED);
    }

    @Test
    void aDivergenceMovesOnlyAlongItsStatesAndOneIsOpenPerWallet() {
        UUID wallet = wallet(TestWallet.generate().address());
        UUID run = run(wallet, "DIVERGENT", "1 minute");
        UUID open = divergence(wallet, run, "OPEN", "1 minute");

        assertThat(catchThrowable(() -> divergence(wallet, run, "OPEN", "1 minute")))
                .hasStackTraceContaining("uq_real_divergence_open");
        assertThat(update(
                        app,
                        "update real_divergence set state = 'RESOLVED', resolved_at = now() where"
                                + " divergence_id = '" + open + "'"))
                .isEqualTo(DENIED);
        assertThat(update(app, "update real_divergence set state = 'COMPENSATED' where divergence_id = '" + open + "'"))
                .isEqualTo("none");
        assertThat(update(
                        app,
                        "update real_divergence set state = 'RESOLVED', resolved_at = now() where"
                                + " divergence_id = '" + open + "'"))
                .isEqualTo("none");
        assertThat(update(app, "update real_divergence set state = 'OPEN' where divergence_id = '" + open + "'"))
                .isEqualTo(DENIED);
        assertThat(update(
                        app,
                        "update real_divergence set bot_wallet_id = gen_random_uuid() where divergence_id = '" + open
                                + "'"))
                .isEqualTo(DENIED);
    }

    @Test
    void theKillSwitchIsOneRowThatTheApplicationOnlyUpdates() {
        assertThat(jdbc.sql("select count(*) from real_kill_switch")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(update(
                        app,
                        "insert into real_kill_switch (switch_id, active, version, updated_at) values (2,"
                                + " false, 0, now())"))
                .isEqualTo(DENIED);
        assertThat(update(app, "delete from real_kill_switch")).isEqualTo(DENIED);
        assertThat(update(app, "update real_kill_switch set active = true, activated_at = null"))
                .isEqualTo(CHECK);
    }

    @Test
    void aCommandToTheSignerNeverCarriesAnAddressAndOnlyAnOldConfirmedOneIsDeleted() {
        UUID wallet = wallet(TestWallet.generate().address());
        String insert = "insert into real_pending_txsigner_command (command_id, bot_wallet_id, kind, payload, attempts,"
                + " created_at, confirmed_at) values (gen_random_uuid(), '" + wallet + "', 'DEACTIVATE',"
                + " cast('%s' as jsonb), 0, now() - interval '13 months 1 day', %s)";

        assertThat(update(
                        app,
                        insert.formatted(
                                "{\"botWalletId\":\"" + wallet + "\",\"address\":\""
                                        + TestWallet.generate().address() + "\"}",
                                "null")))
                .isEqualTo(CHECK);
        assertThat(update(
                        app,
                        insert.formatted(
                                "{\"botWalletId\":\"" + wallet + "\"}", "now() - interval '13 months" + " 1 day'")))
                .isEqualTo("none");
        assertThat(update(app, "delete from real_pending_txsigner_command where bot_wallet_id = '" + wallet + "'"))
                .isEqualTo(DENIED);
        assertThat(update(
                        retention, "delete from real_pending_txsigner_command where bot_wallet_id = '" + wallet + "'"))
                .isEqualTo("none");
    }

    @Test
    void oneRestoreMarkerIsOpenAtATime() {
        String insert = "insert into real_restore_marker (marker_id, phase, opened_at) values (gen_random_uuid(),"
                + " 'CHECK_PENDING', now())";
        owner.sql("update real_restore_marker set cleared_at = now() where cleared_at is null")
                .update();

        assertThat(update(app, insert)).isEqualTo("none");
        assertThat(update(app, insert)).isEqualTo(UNIQUE);
        assertThat(update(app, "delete from real_restore_marker")).isEqualTo(DENIED);
        app.sql("update real_restore_marker set cleared_at = now() where cleared_at is null")
                .update();
    }

    // ----- the precondition, the backfill and the rollbacks on a database of its own -----

    @Test
    void everyChangesetDeclaresItsRollbackAndTheDuplicatesPreconditionComesFirst() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        int changesets = (int)
                Pattern.compile("(?m)^--changeset ").matcher(sql).results().count();

        assertThat(changesets).isGreaterThanOrEqualTo(10);
        assertThat(Pattern.compile("(?m)^--changeset [^\\n]*\\n(?:(?!--changeset )[\\s\\S])*?--rollback ")
                        .matcher(sql)
                        .results()
                        .count())
                .isEqualTo(changesets);
        Matcher first = Pattern.compile("(?m)^--changeset (\\S+)").matcher(sql);
        assertThat(first.find()).isTrue();
        assertThat(first.group(1)).isEqualTo("memetrade:0021-real-release-no-duplicates");
        assertThat(sql).contains("--precondition-sql-check expectedResult:0");
    }

    @Test
    @SuppressWarnings("deprecation")
    void theMigrationStopsOnDuplicatesBackfillsTheStatesAndEveryRollbackRuns() throws Exception {
        String dbName = "u5ops_" + UUID.randomUUID().toString().replace("-", "");
        connect(SUPERUSER).sql("create database " + dbName).update();
        String url = POSTGRES.getJdbcUrl().replace("/memetrade", "/" + dbName);
        bootstrap(dbName);
        migrate(url);
        JdbcClient ownDb = connect(OWNER, url);
        JdbcClient ownApp = connect(APP, url);
        int changesets = changesetsFromThisMigrationOn();
        rollback(url, changesets);
        assertThat(columnExists(ownDb, "real_release", "state")).isFalse();
        assertThat(ownDb.sql("select count(*) from pg_proc where proname = 'redact_real_wallet_audit'")
                        .query(Long.class)
                        .single())
                .isZero();

        TestAccounts.Seeded person =
                TestAccounts.betaUser(ownApp, TestWallet.generate().address());
        UUID revoked = release(ownApp, person.userId(), "LOCALNET", "30 hours", true);
        UUID passed = release(ownApp, person.userId(), "DEVNET", "25 hours", false);
        UUID waiting = release(ownApp, person.userId(), "MAINNET", "1 hour", false);
        UUID duplicate = release(ownApp, person.userId(), "MAINNET", "2 hours", false);

        assertThatThrownBy(() -> migrate(url)).hasStackTraceContaining("SQL Precondition failed");
        assertThat(columnExists(ownDb, "real_release", "state")).isFalse();

        ownApp.sql("update real_release set revoked_at = now() where release_id = :id")
                .param("id", duplicate)
                .update();
        assertThatCode(() -> migrate(url)).doesNotThrowAnyException();

        assertThat(stateOf(ownDb, revoked)).isEqualTo("REVOKED");
        assertThat(stateOf(ownDb, passed)).isEqualTo("ACTIVE");
        assertThat(stateOf(ownDb, waiting)).isEqualTo("QUARANTINE");
        assertThat(stateOf(ownDb, duplicate)).isEqualTo("REVOKED");
    }

    @Test
    @SuppressWarnings("deprecation")
    void theRollbackOfTheWithdrawalCheckStopsWhenAWithdrawalWasRedacted() throws Exception {
        String dbName = "u5opsr_" + UUID.randomUUID().toString().replace("-", "");
        connect(SUPERUSER).sql("create database " + dbName).update();
        String url = POSTGRES.getJdbcUrl().replace("/memetrade", "/" + dbName);
        bootstrap(dbName);
        migrate(url);
        JdbcClient ownApp = connect(APP, url);
        TestAccounts.Seeded person =
                TestAccounts.betaUser(ownApp, TestWallet.generate().address());
        UUID wallet = UUID.randomUUID();
        ownApp.sql("insert into bot_wallet_account (bot_wallet_id, tenant_id, user_id, public_address,"
                        + " main_wallet_address, network, created_at) values (:w, :t, :u, :a, :m, 'LOCALNET', now())")
                .param("w", wallet)
                .param("t", person.tenantId())
                .param("u", person.userId())
                .param("a", TestWallet.generate().address())
                .param("m", TestWallet.generate().address())
                .update();
        ownApp.sql("insert into withdrawal (withdrawal_id, tenant_id, bot_wallet_id, kind, lamports, fee_lamports,"
                        + " state, idempotency_key, signing_request_id, next_attempt_at, requested_at, updated_at,"
                        + " redacted_at) values (gen_random_uuid(), :t, :w, 'TOTAL', 1000, 5000, 'CONFIRMED',"
                        + " gen_random_uuid(), gen_random_uuid(), now(), now(), now(), now())")
                .param("t", person.tenantId())
                .param("w", wallet)
                .update();
        int changesets = changesetsFromThisMigrationOn();

        assertThat(catchThrowable(() -> rollback(url, changesets)))
                .hasStackTraceContaining("withdrawal rows were redacted");
    }

    private static UUID release(JdbcClient login, UUID userId, String network, String age, boolean revoked) {
        UUID id = UUID.randomUUID();
        login.sql("insert into real_release (release_id, user_id, network, requested_by, requested_at, effective_at,"
                        + " revoked_at) values (:id, :u, :n, :u, now() - cast(:age as interval),"
                        + " now() - cast(:age as interval) + interval '24 hours', case when :revoked then now() end)")
                .param("id", id)
                .param("u", userId)
                .param("n", network)
                .param("age", age)
                .param("revoked", revoked)
                .update();
        return id;
    }

    private static String stateOf(JdbcClient login, UUID release) {
        return login.sql("select state from real_release where release_id = :id")
                .param("id", release)
                .query(String.class)
                .single();
    }

    private static boolean columnExists(JdbcClient login, String table, String column) {
        return login.sql("select count(*) from information_schema.columns where table_name = :t and column_name = :c")
                        .param("t", table)
                        .param("c", column)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /**
     * 261113-trading-real-bot U7: the changesets of 0021 and of every later migration of the master changelog, so the
     * rollback reaches 0021 whatever came after it (0022 of the U7 is rolled back first, on an empty table).
     */
    private static int changesetsFromThisMigrationOn() throws java.io.IOException {
        Path changelog = Path.of("src", "main", "resources", "db", "changelog");
        String master = Files.readString(changelog.resolve("db.changelog-master.yaml"), StandardCharsets.UTF_8);
        Matcher includes =
                Pattern.compile("file:\\s*db/changelog/changes/(\\S+\\.sql)").matcher(master);
        int count = 0;
        boolean counting = false;
        while (includes.find()) {
            counting =
                    counting || includes.group(1).equals(MIGRATION.getFileName().toString());
            if (counting) {
                count += (int) Pattern.compile("(?m)^--changeset ")
                        .matcher(Files.readString(changelog.resolve("changes").resolve(includes.group(1))))
                        .results()
                        .count();
            }
        }
        return count;
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
