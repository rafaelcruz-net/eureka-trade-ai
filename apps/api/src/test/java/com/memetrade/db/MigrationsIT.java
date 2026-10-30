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
            "shedlock");

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
