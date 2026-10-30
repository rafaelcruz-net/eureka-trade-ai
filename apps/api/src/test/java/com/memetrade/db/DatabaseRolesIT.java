package com.memetrade.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.testsupport.AbstractPostgresIT;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Roles, ownership and the append-only trigger of U2 (security-design.md: NFR6.2 to NFR6.4, BR1.3, BR3.2). Events
 * are placed relative to the DATABASE clock, which the trigger uses: 13 months (deletable) and 11 months (inside the
 * window), far from the boundary.
 */
@Tag("u2")
class DatabaseRolesIT extends AbstractPostgresIT {

    private final JdbcClient owner = connect(OWNER);
    private final JdbcClient retention = connect(RETENTION);

    // --- application login --------------------------------------------------------------------------------------

    @Test
    void theApplicationReadsAndInsertsTheTrailButNeverUpdatesDeletesOrTruncatesIt() {
        UUID event = insertEvent(jdbc, "13 months");

        assertThat(countEvent(jdbc, event)).isEqualTo(1);
        assertPermissionDenied(() -> jdbc.sql("update audit_event set action = 'CHANGED' where event_id = :id")
                .param("id", event)
                .update());
        assertPermissionDenied(() -> jdbc.sql("delete from audit_event where event_id = :id")
                .param("id", event)
                .update());
        assertPermissionDenied(() -> jdbc.sql("truncate audit_event").update());
        assertThat(countEvent(owner, event)).isEqualTo(1);
    }

    @Test
    void theApplicationHasNoDdlAndCannotSwitchOffTheTrigger() {
        assertRefused(() -> jdbc.sql("create table app_made (id int)").update(), "permission denied");
        assertRefused(() -> jdbc.sql("drop table audit_event").update(), "must be owner");
        assertRefused(
                () -> jdbc.sql("alter table audit_event disable trigger audit_event_no_update_delete")
                        .update(),
                "must be owner");
        assertRefused(() -> jdbc.sql("alter table tenant add column x int").update(), "must be owner");
    }

    @Test
    void theApplicationCannotTakeTheRetentionRole() {
        assertRefused(() -> jdbc.sql("set role audit_retention").update(), "permission denied");
        assertThat(jdbc.sql("select pg_has_role(current_user, 'audit_retention', 'MEMBER')")
                        .query(Boolean.class)
                        .single())
                .isFalse();
    }

    @Test
    void theApplicationKeepsFullDataAccessToItsOtherTables() {
        UUID tenant = UUID.randomUUID();
        jdbc.sql("insert into tenant (tenant_id, name, status, created_at) values (:id, 't', 'ACTIVE', now())")
                .param("id", tenant)
                .update();
        jdbc.sql("update tenant set name = 'u' where tenant_id = :id")
                .param("id", tenant)
                .update();

        assertThat(jdbc.sql("select name from tenant where tenant_id = :id")
                        .param("id", tenant)
                        .query(String.class)
                        .single())
                .isEqualTo("u");
        for (String table : List.of("audit_job_run", "shedlock", "audit_outbox")) {
            assertThat(privilege(APP.user(), table, "INSERT")).as(table).isTrue();
            assertThat(privilege(APP.user(), table, "DELETE")).as(table).isTrue();
        }
    }

    // --- retention login ----------------------------------------------------------------------------------------

    @Test
    void theRetentionLoginDeletesAnEventOlderThan12MonthsAndNothingInsideTheWindow() {
        UUID old = insertEvent(jdbc, "13 months");
        UUID recent = insertEvent(jdbc, "11 months");

        assertThat(retention
                        .sql("delete from audit_event where event_id = :id")
                        .param("id", old)
                        .update())
                .isEqualTo(1);
        assertRefused(
                () -> retention
                        .sql("delete from audit_event where event_id = :id")
                        .param("id", recent)
                        .update(),
                "inside the 12-month retention window");
        assertThat(countEvent(jdbc, recent)).isEqualTo(1);
    }

    @Test
    void theRetentionLoginNeverInsertsUpdatesOrReadsOtherTables() {
        UUID event = insertEvent(jdbc, "13 months");

        assertPermissionDenied(() -> retention
                .sql("insert into audit_event (event_id, actor, actor_type, action, correlation_id, occurred_at)"
                        + " values (gen_random_uuid(), 'x', 'SYSTEM', 'X', 'c', now())")
                .update());
        assertPermissionDenied(() -> retention
                .sql("update audit_event set action = 'X' where event_id = :id")
                .param("id", event)
                .update());
        assertPermissionDenied(() ->
                retention.sql("select count(*) from tenant").query(Long.class).single());
        assertPermissionDenied(() -> retention.sql("delete from audit_outbox").update());
    }

    // --- owner login and the trigger ----------------------------------------------------------------------------

    @Test
    void updateIsRefusedForEveryLoginIncludingTheOwner() {
        UUID event = insertEvent(jdbc, "13 months");

        assertRefused(
                () -> owner.sql("update audit_event set action = 'X' where event_id = :id")
                        .param("id", event)
                        .update(),
                "append-only: UPDATE");
    }

    @Test
    void truncateIsRefusedEvenForTheOwner() {
        assertRefused(() -> owner.sql("truncate audit_event").update(), "append-only: TRUNCATE");
    }

    @Test
    void deleteIsRefusedForTheOwnerEvenOutsideTheWindow() {
        UUID event = insertEvent(jdbc, "13 months");

        assertRefused(
                () -> owner.sql("delete from audit_event where event_id = :id")
                        .param("id", event)
                        .update(),
                "allowed only to the retention role");
        assertThat(countEvent(owner, event)).isEqualTo(1);
    }

    @Test
    void theTriggerFunctionIsNotSecurityDefinerAndOnlyTheOwnerOwnsTheSchema() {
        assertThat(owner.sql("select prosecdef from pg_proc where proname = 'audit_event_reject_change'")
                        .query(Boolean.class)
                        .single())
                .isFalse();
        assertThat(owner.sql("select nspowner::regrole::text from pg_namespace where nspname = 'public'")
                        .query(String.class)
                        .single())
                .isEqualTo(OWNER.user());
        assertThat(owner.sql("select count(*) from pg_tables where schemaname = 'public' and tableowner <> :o")
                        .param("o", OWNER.user())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void theU2SchemaHasTheNewColumnIndexesTablesAndAutovacuumSettings() {
        assertThat(owner.sql("select indexname from pg_indexes where tablename in ('audit_event', 'audit_outbox')")
                        .query(String.class)
                        .list())
                .contains(
                        "audit_event_time_id_idx",
                        "audit_event_action_time_id_idx",
                        "audit_event_recorded_idx",
                        "audit_outbox_delivered_idx")
                .doesNotContain("audit_event_action_time_idx");
        assertThat(owner.sql("select array_to_string(reloptions, ',') from pg_class where relname = 'audit_event'")
                        .query(String.class)
                        .single())
                .contains("autovacuum_vacuum_scale_factor=0.01")
                .contains("autovacuum_analyze_scale_factor=0.005");
        assertThat(owner.sql("select data_type from information_schema.columns"
                                + " where table_name = 'audit_event' and column_name = 'recorded_at'")
                        .query(String.class)
                        .single())
                .isEqualTo("timestamp with time zone");
    }

    // --- bootstrap ----------------------------------------------------------------------------------------------

    @Test
    void theBootstrapRunsTwiceWithoutErrorAndNeverRestoresTheRevokedPrivileges() {
        assertThatCode(() -> {
                    bootstrap("memetrade");
                    bootstrap("memetrade");
                })
                .doesNotThrowAnyException();

        assertThat(privilege(APP.user(), "audit_event", "UPDATE")).isFalse();
        assertThat(privilege(APP.user(), "audit_event", "DELETE")).isFalse();
        assertThat(privilege(APP.user(), "audit_event", "INSERT")).isTrue();
        assertThat(privilege(RETENTION.user(), "audit_event", "DELETE")).isTrue();
        assertThat(jdbc.sql("select 1").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void futureTablesOfTheOwnerAreUsableByTheApplication() {
        String table = "future_" + UUID.randomUUID().toString().replace("-", "");
        owner.sql("create table " + table + " (id int primary key)").update();
        try {
            jdbc.sql("insert into " + table + " values (1)").update();
            assertThat(jdbc.sql("select count(*) from " + table)
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
        } finally {
            owner.sql("drop table " + table).update();
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void aDatabaseInTheU1FormatMovesToTheOwnerAndKeepsWorking() throws Exception {
        String dbName = "u1_" + UUID.randomUUID().toString().replace("-", "");
        connect(SUPERUSER).sql("create database " + dbName).update();
        String url = POSTGRES.getJdbcUrl().replace("/memetrade", "/" + dbName);
        connect(SUPERUSER, url)
                .sql("alter schema public owner to " + APP.user())
                .update();
        try (Connection app = DriverManager.getConnection(url, APP.user(), APP.password())) {
            Database database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(app));
            new Liquibase("db/changelog/u1-only.yaml", new ClassLoaderResourceAccessor(), database)
                    .update(new Contexts());
        }
        JdbcClient u1App = connect(APP, url);
        UUID u1Event = UUID.randomUUID();
        u1App.sql("insert into audit_event (event_id, actor, actor_type, action, correlation_id, occurred_at)"
                        + " values (:id, 'system', 'SYSTEM', 'U1_EVENT', 'c', now())")
                .param("id", u1Event)
                .update();

        bootstrap(dbName);
        migrate(url);
        bootstrap(dbName);

        JdbcClient u2Owner = connect(OWNER, url);
        assertThat(u2Owner.sql("select count(*) from pg_tables where schemaname = 'public' and tableowner <> :o")
                        .param("o", OWNER.user())
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(u2Owner.sql("select recorded_at = occurred_at from audit_event where event_id = :id")
                        .param("id", u1Event)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(u2Owner.sql("select has_table_privilege(:u, 'audit_event', 'UPDATE')")
                        .param("u", APP.user())
                        .query(Boolean.class)
                        .single())
                .isFalse();
        assertThat(u2Owner.sql("select count(*) from pg_default_acl")
                        .query(Long.class)
                        .single())
                .isPositive();
        u1App.sql("insert into tenant (tenant_id, name, status, created_at) values (gen_random_uuid(), 't',"
                        + " 'ACTIVE', now())")
                .update();
        assertRefused(() -> u1App.sql("create table after_u2 (id int)").update(), "permission denied");
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private static UUID insertEvent(JdbcClient client, String age) {
        UUID id = UUID.randomUUID();
        client.sql("insert into audit_event"
                        + " (event_id, actor, actor_type, action, correlation_id, occurred_at, recorded_at)"
                        + " values (:id, 'system', 'SYSTEM', 'TEST_EVENT', 'corr', now() - cast(:age as interval),"
                        + " now())")
                .param("id", id)
                .param("age", age)
                .update();
        return id;
    }

    private static long countEvent(JdbcClient client, UUID id) {
        return client.sql("select count(*) from audit_event where event_id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private boolean privilege(String user, String table, String privilege) {
        return owner.sql("select has_table_privilege(:u, :t, :p)")
                .param("u", user)
                .param("t", table)
                .param("p", privilege)
                .query(Boolean.class)
                .single();
    }

    private static void assertPermissionDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertRefused(call, "permission denied");
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String message) {
        assertThatThrownBy(call)
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining(message);
    }
}
