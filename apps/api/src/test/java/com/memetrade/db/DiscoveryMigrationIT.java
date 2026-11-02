package com.memetrade.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Migration 0006-discovery (Step 3): constraints, privileges, the weight seed and the move from a U2 database. */
@Tag("u4")
class DiscoveryMigrationIT extends AbstractPostgresIT {

    private static final List<String> U4_TABLES =
            List.of("observation_sample", "risk_weight_set", "risk_assessment", "source_status_mark");

    @Test
    void theApplicationLoginReadsAndWritesTheNewTablesButHasNoDdl() {
        for (String table : U4_TABLES) {
            assertThat(jdbc.sql("select has_table_privilege(current_user, :t, 'SELECT,INSERT,UPDATE,DELETE')")
                            .param("t", table)
                            .query(Boolean.class)
                            .single())
                    .as(table)
                    .isTrue();
        }
        assertThatThrownBy(() -> jdbc.sql("alter table observation_sample add column x int")
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("must be owner");
    }

    @Test
    void aSampleIsKnownWithAValueOrUnknownWithAReasonAndNeverNegativeOrTooPrecise() {
        String mint = token();
        source("mig-sample");

        assertThatCode(() -> sample(mint, "PRICE", "KNOWN", "0.000000000000000001", null, "12:00:00"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> sample(mint, "LIQUIDITY", "KNOWN", null, null, "12:00:00"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> sample(mint, "HOLDERS", "UNKNOWN", "1", "NOT_PROVIDED", "12:00:00"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> sample(mint, "VOLUME_24H", "KNOWN", "-1", null, "12:00:00"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> sample(mint, "VOLUME_24H", "KNOWN", "0.0000000000000000001", null, "12:00:00"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> sample(mint, "CREATED_AT", "KNOWN", "1", null, "12:00:00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oneSamplePerTokenSourceFieldAndFiveMinuteBucket() {
        String mint = token();
        source("mig-bucket");
        sample(mint, "PRICE", "KNOWN", "1", null, "12:00:10");

        assertThatThrownBy(() -> sample(mint, "PRICE", "KNOWN", "2", null, "12:04:59"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> sample(mint, "PRICE", "KNOWN", "3", null, "12:05:00"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbc.sql("""
                                insert into observation_sample (sample_id, chain, mint, source_id, field, status, value,
                                  observed_at, bucket_start)
                                values (:id, 'solana', :mint, 'mig-sample', 'HOLDERS', 'KNOWN', 1,
                                  timestamptz '2026-10-28 12:06:00+00', timestamptz '2026-10-28 12:00:00+00')
                                """)
                        .param("id", UUID.randomUUID())
                        .param("mint", mint)
                        .update())
                .as("the observation must fall inside its bucket")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void versionOneIsActiveWithTheWeightsCurvesBandsAndCoverageOfBr43() throws Exception {
        Map<String, Object> v1 = jdbc.sql("select * from risk_weight_set where version = 1")
                .query()
                .singleRow();
        JsonNode factors = new ObjectMapper().readTree(String.valueOf(v1.get("factors")));

        // ACTIVE from the migration; a later test may have activated a newer version (RETIRED then).
        assertThat(v1.get("status")).isIn("ACTIVE", "RETIRED");
        assertThat(v1).containsEntry("created_by", "system");
        assertThat(jdbc.sql("select count(*) from risk_weight_set where status = 'ACTIVE'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(((Number) v1.get("low_max")).intValue()).isEqualTo(39);
        assertThat(((Number) v1.get("medium_max")).intValue()).isEqualTo(69);
        assertThat((BigDecimal) v1.get("min_coverage")).isEqualByComparingTo("80.0000");
        assertThat(factors.findValuesAsText("factorId"))
                .containsExactly("LIQUIDITY", "HOLDERS", "VOLUME_24H", "PRICE_CHANGE_1H");
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode factor : factors) {
            sum = sum.add(new BigDecimal(factor.get("weight").asText()));
            assertThat(factor.get("weight").isTextual()).isTrue();
            assertThat(factor.get("curve")).hasSize(4);
        }
        assertThat(sum).isEqualByComparingTo("100.0000");
        assertThat(factors.get(3).get("curve").get(3).get("input").asText()).isEqualTo("60");
    }

    @Test
    void atMostOneVersionIsActive() {
        assertThatThrownBy(() -> jdbc.sql("""
                                insert into risk_weight_set (version, status, factors, low_max, medium_max,
                                  min_coverage, created_at, created_by, activated_at)
                                values (999999, 'ACTIVE', '[]'::jsonb, 39, 69, 80, now(), 'test', now())
                                """).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                                insert into risk_weight_set (version, status, factors, low_max, medium_max,
                                  min_coverage, created_at, created_by)
                                values (999998, 'DRAFT', '[]'::jsonb, 69, 39, 80, now(), 'test')
                                """).update())
                .as("lowMax < mediumMax")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anAssessmentIsAvailableWithScoreAndBandOrUnavailableWithAReasonAndNoTrade() {
        String mint = token();

        assertThatThrownBy(() -> assessment(mint, "AVAILABLE", null, null, null, "ELIGIBLE", false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () -> assessment(mint, "UNAVAILABLE", null, null, "INSUFFICIENT_COVERAGE", "ELIGIBLE", false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> assessment(mint, "AVAILABLE", 20, "LOW", null, "ELIGIBLE", true))
                .as("bandRaisedByUnknown only with MEDIUM")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> assessment(mint, "AVAILABLE", 101, "HIGH", null, "ELIGIBLE", false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> assessment(mint, "UNAVAILABLE", null, null, "SOURCE_UNAVAILABLE", "NO_TRADE", false))
                .doesNotThrowAnyException();
    }

    @Test
    void theSourceAcceptsPendingACategoryAndAnIntervalOfFiveToSixtySeconds() {
        assertThatCode(() -> jdbc.sql("""
                                insert into data_source (source_id, name, simulated, status, failure_category,
                                  collection_interval_seconds)
                                values ('mig-pending', 'P', true, 'PENDING', null, 5)
                                """).update()).doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbc.sql("update data_source set failure_category = 'BROKEN'"
                                + " where source_id = 'mig-pending'")
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("update data_source set collection_interval_seconds = 61"
                                + " where source_id = 'mig-pending'")
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("update data_source set status = 'UNKNOWN' where source_id = 'mig-pending'")
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theStatusMarkIsASingleRowThatNeverGoesNegative() {
        assertThat(jdbc.sql("select count(*) from source_status_mark")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.sql("insert into source_status_mark (singleton, mark) values (false, 0)")
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() ->
                        jdbc.sql("update source_status_mark set mark = -1").update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @SuppressWarnings("deprecation")
    void aDatabaseInTheU2FormatMovesNeverCollectedSourcesToPendingAndKeepsTheOthers() throws Exception {
        String dbName = "u2_" + UUID.randomUUID().toString().replace("-", "");
        connect(SUPERUSER).sql("create database " + dbName).update();
        String url = POSTGRES.getJdbcUrl().replace("/memetrade", "/" + dbName);
        bootstrap(dbName);
        try (Connection owner = DriverManager.getConnection(url, OWNER.user(), OWNER.password())) {
            Database database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(owner));
            new Liquibase("db/changelog/u2-only.yaml", new ClassLoaderResourceAccessor(), database)
                    .update(new Contexts());
        }
        JdbcClient u2App = connect(APP, url);
        // Exactly what the U2 code wrote: FAILED without a last success (never collected), and OK after a success.
        u2App.sql("insert into data_source (source_id, name, simulated, status, last_updated_at, last_attempt_at)"
                        + " values ('never', 'N', true, 'FAILED', null, now()),"
                        + " ('collected', 'C', true, 'OK', now(), now())")
                .update();

        migrate(url);

        assertThat(u2App.sql("select source_id || ':' || status from data_source order by source_id")
                        .query(String.class)
                        .list())
                .containsExactly("collected:OK", "never:PENDING");
        assertThat(u2App.sql("select status from risk_weight_set where version = 1")
                        .query(String.class)
                        .single())
                .isEqualTo("ACTIVE");
        assertThat(u2App.sql("select mark from source_status_mark")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private String token() {
        String mint = SyntheticIds.mint();
        jdbc.sql("insert into token (chain, mint, symbol, decimals, first_seen_at)"
                        + " values ('solana', :mint, 'MIG', 6, now())")
                .param("mint", mint)
                .update();
        return mint;
    }

    private void source(String sourceId) {
        jdbc.sql("insert into data_source (source_id, name, simulated, status) values (:id, 'M', true, 'OK')"
                        + " on conflict do nothing")
                .param("id", sourceId)
                .update();
    }

    private void sample(String mint, String field, String status, String value, String reason, String time) {
        String sourceId = "mig-sample";
        source(sourceId);
        jdbc.sql("""
                        insert into observation_sample (sample_id, chain, mint, source_id, field, status, value,
                          unknown_reason, observed_at, bucket_start)
                        values (:id, 'solana', :mint, :source, :field, :status, cast(:value as numeric), :reason,
                          cast(:observed as timestamptz),
                          date_bin(interval '5 minutes', cast(:observed as timestamptz), timestamptz 'epoch'))
                        """)
                .param("id", UUID.randomUUID())
                .param("mint", mint)
                .param("source", sourceId)
                .param("field", field)
                .param("status", status)
                .param("value", value)
                .param("reason", reason)
                .param("observed", "2026-10-28 " + time + "+00")
                .update();
    }

    private void assessment(
            String mint, String status, Integer score, String band, String reason, String hint, boolean raised) {
        jdbc.sql("""
                        insert into risk_assessment (chain, mint, assessment_id, weight_set_version, status, score,
                          band, band_raised_by_unknown, unavailable_reason, coverage, decision_hint, factors,
                          inputs_fingerprint, computed_at)
                        values ('solana', :mint, :id, 1, :status, :score, :band, :raised, :reason, 80.0000, :hint,
                          '[]'::jsonb, 'fp', now())
                        """)
                .param("mint", mint)
                .param("id", UUID.randomUUID())
                .param("status", status)
                .param("score", score)
                .param("band", band)
                .param("raised", raised)
                .param("reason", reason)
                .param("hint", hint)
                .update();
    }
}
