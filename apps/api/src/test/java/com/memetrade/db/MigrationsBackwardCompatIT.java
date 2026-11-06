package com.memetrade.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * U8 rollback test (infrastructure-specification.md "Rollback (Q4)", review R-09 of the Infrastructure Design): a
 * database in the format of the previous image (U5, changelog 0001 to 0010) gets 0011 applied by db-migrate, and
 *
 * <ul>
 *   <li>no column of the previous tables disappears or changes type or nullability (expand only);
 *   <li>the writes and reads of the previous image (guest session, account seed, bots, the U5 copy of the kill switch)
 *       keep working with the application login;
 *   <li>an ADMIN session row, written by U8, is accepted by the widened check and is simply not readable as a guest by
 *       the previous image (it filters nothing by role, the role column only gains a value).
 * </ul>
 *
 * The previous image itself is not started here: its SQL, verbatim from the U5 repositories, runs on the migrated
 * schema, which is what the previous context does at start-up and on the first requests.
 */
@Tag("u8")
class MigrationsBackwardCompatIT extends AbstractPostgresIT {

    private static String url;
    private static Set<String> previousColumns;

    @BeforeAll
    @SuppressWarnings("deprecation")
    static void migrateAPreviousDatabaseToU8() throws Exception {
        String dbName = "u5_" + UUID.randomUUID().toString().replace("-", "");
        connect(SUPERUSER).sql("create database " + dbName).update();
        url = POSTGRES.getJdbcUrl().replace("/memetrade", "/" + dbName);
        bootstrap(dbName);
        try (Connection owner = DriverManager.getConnection(url, OWNER.user(), OWNER.password())) {
            Database database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(owner));
            new Liquibase("db/changelog/u5-only.yaml", new ClassLoaderResourceAccessor(), database)
                    .update(new Contexts());
        }
        previousColumns = columns(connect(OWNER, url));
        migrate(url);
    }

    @Test
    void noPreviousColumnDisappearsOrChangesItsTypeOrNullability() {
        Set<String> now = columns(connect(OWNER, url));

        assertThat(previousColumns).isNotEmpty();
        assertThat(now).containsAll(previousColumns);
        assertThat(now)
                .anyMatch(column -> column.startsWith("kill_switch_state.pending_bots:"))
                .anyMatch(column -> column.startsWith("feedback_entry.text:"));
    }

    @Test
    void thePreviousGuestSessionAndAccountWritesStillWork() {
        JdbcClient app = connect(APP, url);
        String address = SyntheticIds.mint();
        TestAccounts.Seeded guest = TestAccounts.betaUser(app, address);

        // JdbcSessionRepository.insert of the previous image, verbatim.
        assertThatCode(() -> app.sql("""
                                insert into user_session
                                  (session_id, user_id, tenant_id, role, refresh_token_digest, created_at,
                                   refresh_expires_at, absolute_expires_at, state, revoked_at)
                                values (:id, :user, :tenant, :role, :digest, :created, :refresh, :absolute, :state,
                                        null)
                                """)
                        .param("id", UUID.randomUUID())
                        .param("user", guest.userId())
                        .param("tenant", guest.tenantId())
                        .param("role", "BETA_USER")
                        .param("digest", "a".repeat(64))
                        .param("created", Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")))
                        .param("refresh", Timestamp.from(Instant.parse("2026-11-01T08:00:00Z")))
                        .param("absolute", Timestamp.from(Instant.parse("2026-11-01T12:00:00Z")))
                        .param("state", "ACTIVE")
                        .update())
                .doesNotThrowAnyException();
        assertThat(app.sql("""
                                select u.role from wallet w join app_user u on u.user_id = w.user_id
                                where w.address = :address
                                """).param("address", address).query(String.class).single())
                .isEqualTo("BETA_USER");
    }

    @Test
    void thePreviousReadsOfBotsAndTheSystemPauseStillRun() {
        JdbcClient app = connect(APP, url);
        for (String query : List.of(
                "select * from system_pause_state",
                "select * from bot order by bot_id limit 1",
                "select * from login_challenge where nonce = 'x'",
                "select * from invite order by created_at desc, invite_id limit 1",
                "select count(*) from audit_outbox where delivered_at is null")) {
            assertThatCode(() -> app.sql(query).query().listOfRows()).as(query).doesNotThrowAnyException();
        }
    }

    @Test
    void theWidenedRoleCheckAcceptsAdminAndStillRefusesAnythingElse() {
        JdbcClient app = connect(APP, url);
        TestAccounts.Seeded someone = TestAccounts.betaUser(app, SyntheticIds.mint());

        assertThatCode(() -> insertSession(app, someone, "ADMIN")).doesNotThrowAnyException();
        assertThatThrownBy(() -> insertSession(app, someone, "OWNER"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("user_session_role_ck");
    }

    private static void insertSession(JdbcClient app, TestAccounts.Seeded who, String role) {
        app.sql("""
                        insert into user_session
                          (session_id, user_id, tenant_id, role, refresh_token_digest, created_at,
                           refresh_expires_at, absolute_expires_at, state, revoked_at)
                        values (:id, :user, :tenant, :role, :digest, now(), now() + interval '8 hours',
                                now() + interval '8 hours', 'ACTIVE', null)
                        """)
                .param("id", UUID.randomUUID())
                .param("user", who.userId())
                .param("tenant", who.tenantId())
                .param("role", role)
                .param("digest", UUID.randomUUID().toString().replace("-", "") + "0".repeat(32))
                .update();
    }

    /** {@code table.column:type:nullable} of every column of the public schema. */
    private static Set<String> columns(JdbcClient jdbc) {
        return new TreeSet<>(jdbc.sql("""
                        select table_name || '.' || column_name || ':' || data_type || ':' || is_nullable
                        from information_schema.columns
                        where table_schema = 'public' and table_name not like 'databasechangelog%'
                        """).query(String.class).list());
    }
}
