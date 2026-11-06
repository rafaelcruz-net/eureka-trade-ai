package com.memetrade.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

class MigrationsIT extends AbstractPostgresIT {

    private static final List<String> TABLES = List.of(
            "tenant",
            "app_user",
            "wallet",
            "login_challenge",
            "user_session",
            "session_used_refresh_digest",
            "idempotency_record",
            "token",
            "data_source",
            "market_observation",
            "audit_outbox",
            "audit_event",
            "audit_job_run",
            "shedlock",
            "observation_sample",
            "risk_weight_set",
            "risk_assessment",
            "source_status_mark",
            "invite",
            "terms_acceptance",
            // U7 (migration 0008).
            "simulation_policy",
            "paper_account",
            "paper_order",
            "fill",
            "ledger_entry",
            "position",
            "pnl_daily",
            "equity_peak",
            "reconciliation_check",
            // U6 (migration 0009).
            "order_evaluation",
            "order_rejection",
            "rejection_reason_detail",
            "risk_purge_run",
            // U5 (migration 0010).
            "strategy_template",
            "bot_owner",
            "bot",
            "bot_limits_version",
            "bot_cycle",
            "bot_holding",
            "system_pause_state",
            "decision",
            // U8 (migration 0011).
            "kill_switch_state",
            "kill_switch_change",
            "feedback_entry");

    /**
     * U8 rollback test (cicd-pipeline.md "Implantação e rollback"): the queries of U1 to U7, U6 and U5 keep running on
     * the schema migrated by 0011, which only adds tables, indexes, triggers and a grant and widens the role check of
     * user_session; the kill switch row exists OFF and the guest session of the previous image is still written.
     */
    @Test
    @org.junit.jupiter.api.Tag("u8")
    void thePreviousQueriesStillRunOnTheU8SchemaAndTheKillSwitchStartsOff() {
        List<String> all = new java.util.ArrayList<>(PREVIOUS_ENTRY_AND_SESSION_QUERIES);
        all.addAll(PREVIOUS_LISTING_QUERIES);
        all.add("select * from bot order by bot_id limit 1");
        all.add("select * from system_pause_state");
        all.add("select * from order_rejection order by evaluated_at desc limit 1");
        all.add("select * from shedlock");
        for (String query : all) {
            assertThatCode(() -> jdbc.sql(query).query().listOfRows()).as(query).doesNotThrowAnyException();
        }
        assertThat(jdbc.sql("select count(*) from kill_switch_state")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from databasechangelog where id like '0011-%'")
                        .query(Long.class)
                        .single())
                .isEqualTo(6);
        assertThat(jdbc.sql("""
                                select pg_get_constraintdef(oid) from pg_constraint
                                where conname = 'user_session_role_ck'
                                """).query(String.class).single())
                .contains("BETA_USER")
                .contains("ADMIN");
    }

    /**
     * U5 rollback test (cicd-pipeline.md "Implantação e rollback"): the queries of U1 to U7 and U6 keep running on the
     * schema migrated by 0010, which only adds tables, indexes, a sequence and seeds (the U6 image runs on it with the
     * new tables unused).
     */
    @Test
    @org.junit.jupiter.api.Tag("u5")
    void thePreviousQueriesOfU1ToU7AndU6StillRunOnTheU5Schema() {
        List<String> all = new java.util.ArrayList<>(PREVIOUS_ENTRY_AND_SESSION_QUERIES);
        all.addAll(PREVIOUS_LISTING_QUERIES);
        all.add("select * from order_evaluation order by evaluated_at desc limit 1");
        all.add("select * from order_rejection order by evaluated_at desc limit 1");
        all.add("select * from paper_order order by created_at desc, order_id desc limit 1");
        all.add("select * from shedlock");
        for (String query : all) {
            assertThatCode(() -> jdbc.sql(query).query().listOfRows()).as(query).doesNotThrowAnyException();
        }
        assertThat(jdbc.sql("select count(*) from databasechangelog where id like '0010-%'")
                        .query(Long.class)
                        .single())
                .isPositive();
    }

    /**
     * U6 rollback test (cicd-pipeline.md): the queries of U1 to U7 keep running on the schema migrated by 0009, which
     * only adds tables, indexes, triggers and privileges (the U7 image runs on it with the new tables unused).
     */
    @Test
    @org.junit.jupiter.api.Tag("u6")
    void thePreviousQueriesOfU1ToU7StillRunOnTheU6Schema() {
        List<String> all = new java.util.ArrayList<>(PREVIOUS_ENTRY_AND_SESSION_QUERIES);
        all.addAll(PREVIOUS_LISTING_QUERIES);
        all.add("select * from invite order by created_at desc, invite_id limit 1");
        all.add("select count(*) from audit_outbox where delivered_at is null");
        all.add("select * from simulation_policy where version = 'v1'");
        all.add("select * from paper_order order by created_at desc, order_id desc limit 1");
        all.add(
                "select coalesce(sum(amount), 0) from ledger_entry where bot_id = '00000000-0000-0000-0000-000000000000'");
        all.add("select * from position where quantity > 0 limit 1");
        all.add("select * from equity_peak limit 1");
        all.add("select * from pnl_daily limit 1");
        all.add("select * from shedlock");
        for (String query : all) {
            assertThatCode(() -> jdbc.sql(query).query().listOfRows()).as(query).doesNotThrowAnyException();
        }
        assertThat(jdbc.sql("select count(*) from databasechangelog where id like '0009-%'")
                        .query(Long.class)
                        .single())
                .isPositive();
    }

    /**
     * U7 rollback test (cicd-pipeline.md): the queries and writes of U1 to U4 keep running on the schema migrated by
     * 0008, which only adds tables, indexes, triggers, privileges and the policy v1.
     */
    @Test
    @org.junit.jupiter.api.Tag("u7")
    void thePreviousQueriesOfU1ToU4StillRunOnTheU7Schema() {
        List<String> all = new java.util.ArrayList<>(PREVIOUS_ENTRY_AND_SESSION_QUERIES);
        all.addAll(PREVIOUS_LISTING_QUERIES);
        all.add("select * from invite order by created_at desc, invite_id limit 1");
        all.add("select count(*) from audit_outbox where delivered_at is null");
        all.add("select * from idempotency_record limit 1");
        for (String query : all) {
            assertThatCode(() -> jdbc.sql(query).query().listOfRows()).as(query).doesNotThrowAnyException();
        }
        List<String> existing = jdbc.sql(
                        "select table_name from information_schema.tables where table_schema = 'public'")
                .query(String.class)
                .list();
        assertThat(existing).containsAll(TABLES);
        assertThat(jdbc.sql("select count(*) from databasechangelog where id like '0008-%'")
                        .query(Long.class)
                        .single())
                .isPositive();
    }

    /**
     * U3 rollback test (cicd-pipeline.md): the entry and session SQL of U1, verbatim from JdbcAccountRepository and
     * JdbcSessionRepository before U3, must keep running on the schema migrated by 0007, so the U4 image works on it.
     */
    private static final List<String> PREVIOUS_ENTRY_AND_SESSION_QUERIES =
            List.of("""
            select u.user_id, u.tenant_id, u.role, u.status as user_status, t.status as tenant_status
            from wallet w
            join app_user u on u.user_id = w.user_id
            join tenant t on t.tenant_id = u.tenant_id
            where w.address = 'x'
            """, """
            select s.session_id, s.user_id, s.tenant_id, s.role, s.state, s.absolute_expires_at,
                   u.status as user_status, t.status as tenant_status
            from user_session s
            join app_user u on u.user_id = s.user_id
            join tenant t on t.tenant_id = s.tenant_id
            where s.session_id = '00000000-0000-0000-0000-000000000000'
            """, "select * from login_challenge where nonce = 'x'");

    /**
     * The SQL of the U1/U2 listing and collector, verbatim from the release before U4 (rollback test of
     * cicd-pipeline.md: the previous api image must keep working on the migrated database).
     */
    private static final List<String> PREVIOUS_LISTING_QUERIES = List.of(
            "select count(*) from token where first_seen_at <= now()",
            "select * from data_source order by source_id",
            "select chain, mint, symbol, name, sort_group, sort_key from token where first_seen_at <= now()"
                    + " and (sort_group, sort_key, mint, chain) > (0, 0, '', '')"
                    + " order by sort_group, sort_key, mint, chain limit 51",
            """
            select o.chain, o.mint, o.field, o.status, o.value, o.unknown_reason, o.observed_at,
                   s.source_id, s.name, s.simulated
            from market_observation o join data_source s on s.source_id = o.source_id
            where o.mint in ('x')
            """);

    @Test
    void changelogAppliesFromScratchOnAnEmptyDatabase() throws Exception {
        // U2: the application login cannot create databases; the superuser does, the bootstrap prepares the roles
        // and the owner applies the changelog, exactly like db-bootstrap and db-migrate on a new volume.
        String dbName = "fresh_" + UUID.randomUUID().toString().replace("-", "");
        connect(SUPERUSER).sql("CREATE DATABASE " + dbName).update();
        String url = POSTGRES_URL().replace("/memetrade", "/" + dbName);
        bootstrap(dbName);
        migrate(url);

        try (Connection connection = DriverManager.getConnection(url, OWNER.user(), OWNER.password())) {
            var rs = connection
                    .createStatement()
                    .executeQuery("select count(*) from information_schema.tables where table_schema = 'public'"
                            + " and table_name in ('" + String.join("','", TABLES) + "')");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(TABLES.size());
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("u4")
    void thePreviousListingQueriesStillRunOnTheU4Schema() {
        for (String query : PREVIOUS_LISTING_QUERIES) {
            assertThatCode(() -> jdbc.sql(query).query().listOfRows()).as(query).doesNotThrowAnyException();
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("u3")
    void thePreviousEntryAndSessionQueriesStillRunOnTheU3Schema() {
        for (String query : PREVIOUS_ENTRY_AND_SESSION_QUERIES) {
            assertThatCode(() -> jdbc.sql(query).query().listOfRows()).as(query).doesNotThrowAnyException();
        }
        // The U1 smoke seeder inserts a tenant, a user and a wallet exactly like this (rollback image on the new
        // schema).
        String address = com.memetrade.testsupport.TestWallet.generate().address();
        assertThatCode(() -> com.memetrade.testsupport.TestAccounts.betaUser(jdbc, address))
                .doesNotThrowAnyException();
    }

    @Test
    @org.junit.jupiter.api.Tag("u4")
    void thePreviousCollectorWritesStillRunOnTheU4Schema() {
        String mint = SyntheticIds.mint();
        // U1 JdbcMarketStore.sourceCollected / sourceFailed, verbatim (the previous image after a rollback).
        String collected = """
                insert into data_source (source_id, name, simulated, status, last_updated_at, last_attempt_at)
                values ('rollback-src', 'R', true, 'OK', now(), now())
                on conflict (source_id) do update
                  set name = excluded.name, simulated = excluded.simulated, status = 'OK',
                      last_updated_at = excluded.last_updated_at, last_attempt_at = excluded.last_attempt_at
                """;
        String failed = """
                insert into data_source (source_id, name, simulated, status, last_updated_at, last_attempt_at)
                values ('rollback-src', 'R', true, 'FAILED', null, now())
                on conflict (source_id) do update set status = 'FAILED', last_attempt_at = excluded.last_attempt_at
                """;

        assertThatCode(() -> {
                    jdbc.sql(failed).update();
                    jdbc.sql(collected).update();
                    insertToken("solana:rollback", mint);
                    jdbc.sql("update token set token_created_at = now(), sort_group = 0, sort_key = -1"
                                    + " where chain = 'solana:rollback' and mint = :m")
                            .param("m", mint)
                            .update();
                })
                .doesNotThrowAnyException();
        assertThat(jdbc.sql("select status from data_source where source_id = 'rollback-src'")
                        .query(String.class)
                        .single())
                .isEqualTo("OK");
        jdbc.sql("update data_source set enabled = false where source_id = 'rollback-src'")
                .update();
    }

    @Test
    void everyExpectedTableExists() {
        List<String> existing = jdbc.sql(
                        "select table_name from information_schema.tables where table_schema = 'public'")
                .query(String.class)
                .list();
        assertThat(existing).containsAll(TABLES);
    }

    @Test
    void everyInstantColumnIsTimestampWithTimeZone() {
        List<String> wrong = jdbc.sql("""
                        select table_name || '.' || column_name from information_schema.columns
                        where table_schema = 'public' and column_name like '%\\_at' escape '\\'
                          and table_name not like 'databasechangelog%'
                          and data_type <> 'timestamp with time zone'
                        """).query(String.class).list();
        assertThat(wrong).isEmpty();
    }

    @Test
    void noColumnUsesFloatingPoint() {
        List<String> floats = jdbc.sql("""
                        select table_name || '.' || column_name from information_schema.columns
                        where table_schema = 'public' and data_type in ('real', 'double precision')
                        """).query(String.class).list();
        assertThat(floats).isEmpty();
    }

    @Test
    void tokenIdentityIsChainPlusMint() {
        String mint = SyntheticIds.mint();
        insertToken("solana:test", mint);
        assertThatCode(() -> insertToken("solana:other", mint)).doesNotThrowAnyException();
        assertThatThrownBy(() -> insertToken("solana:test", mint)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOneCurrentObservationPerTokenSourceAndField() {
        String mint = SyntheticIds.mint();
        insertToken("solana:test", mint);
        insertSource("src-unique");
        insertObservation(mint, "src-unique", "PRICE", "KNOWN", "1.5", null);

        assertThatThrownBy(() -> insertObservation(mint, "src-unique", "PRICE", "KNOWN", "2.5", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insertObservation(mint, "src-unique", "LIQUIDITY", "KNOWN", "2.5", null))
                .doesNotThrowAnyException();
    }

    @Test
    void knownRequiresValueAndUnknownRequiresReason() {
        String mint = SyntheticIds.mint();
        insertToken("solana:test", mint);
        insertSource("src-check");

        assertThatThrownBy(() -> insertObservation(mint, "src-check", "PRICE", "KNOWN", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertObservation(mint, "src-check", "HOLDERS", "UNKNOWN", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertObservation(mint, "src-check", "VOLUME_24H", "UNKNOWN", "0", "NOT_PROVIDED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void informedZeroIsAKnownValueAndMissingIsUnknown() {
        String mint = SyntheticIds.mint();
        insertToken("solana:test", mint);
        insertSource("src-zero");

        insertObservation(mint, "src-zero", "LIQUIDITY", "KNOWN", "0", null);
        insertObservation(mint, "src-zero", "HOLDERS", "UNKNOWN", null, "NOT_PROVIDED");

        String liquidity = jdbc.sql(
                        "select value::text from market_observation where mint = :m and field = 'LIQUIDITY'")
                .param("m", mint)
                .query(String.class)
                .single();
        assertThat(liquidity).isEqualTo("0");
    }

    @Test
    void idempotencyScopeAndKeyAreUnique() {
        UUID key = UUID.randomUUID();
        insertIdempotency("wallet:abc:auth.challenge", key);
        assertThatCode(() -> insertIdempotency("wallet:def:auth.challenge", key))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> insertIdempotency("wallet:abc:auth.challenge", key))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void auditEventRejectsUpdateAndDelete() {
        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                        insert into audit_event (event_id, actor, actor_type, action, correlation_id, occurred_at)
                        values (:id, 'system', 'SYSTEM', 'TEST_EVENT', 'corr', now())
                        """).param("id", eventId).update();

        // Since U2 the application login lacks UPDATE and DELETE on audit_event (NFR6.2), so the database refuses
        // before the trigger; the trigger itself (owner and retention paths) is covered by DatabaseRolesIT.
        assertThatThrownBy(() -> jdbc.sql("update audit_event set action = 'CHANGED' where event_id = :id")
                        .param("id", eventId)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table audit_event");
        assertThatThrownBy(() -> jdbc.sql("delete from audit_event where event_id = :id")
                        .param("id", eventId)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table audit_event");
        assertThatThrownBy(() -> connect(OWNER)
                        .sql("update audit_event set action = 'CHANGED' where event_id = :id")
                        .param("id", eventId)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("audit_event is append-only");
        assertThat(jdbc.sql("select action from audit_event where event_id = :id")
                        .param("id", eventId)
                        .query(String.class)
                        .single())
                .isEqualTo("TEST_EVENT");
    }

    @Test
    void enumValuesMustBeUpperCase() {
        assertThatThrownBy(() -> jdbc.sql(
                                "insert into tenant (tenant_id, name, status, created_at) values (:id, 't', 'active', now())")
                        .param("id", UUID.randomUUID())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static String POSTGRES_URL() {
        return POSTGRES.getJdbcUrl();
    }

    private void insertToken(String chain, String mint) {
        jdbc.sql("""
                        insert into token (chain, mint, symbol, decimals, first_seen_at)
                        values (:chain, :mint, 'TST', 6, now())
                        """).param("chain", chain).param("mint", mint).update();
    }

    private void insertSource(String sourceId) {
        jdbc.sql("""
                        insert into data_source (source_id, name, simulated, status)
                        values (:id, 'Test', true, 'OK') on conflict do nothing
                        """).param("id", sourceId).update();
    }

    private void insertObservation(
            String mint, String source, String field, String status, String value, String reason) {
        jdbc.sql("""
                        insert into market_observation
                          (observation_id, chain, mint, source_id, field, status, value, unknown_reason, observed_at)
                        values (:id, 'solana:test', :mint, :source, :field, :status, cast(:value as numeric), :reason, now())
                        """)
                .param("id", UUID.randomUUID())
                .param("mint", mint)
                .param("source", source)
                .param("field", field)
                .param("status", status)
                .param("value", value)
                .param("reason", reason)
                .update();
    }

    private void insertIdempotency(String scope, UUID key) {
        jdbc.sql("""
                        insert into idempotency_record
                          (scope, idempotency_key, request_digest, state, created_at, expires_at)
                        values (:scope, :key, repeat('a', 64), 'IN_PROGRESS', now(), now() + interval '1 hour')
                        """).param("scope", scope).param("key", key).update();
    }
}
