package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.domain.terms.CurrentTerms;
import com.memetrade.identity.domain.terms.TermsDocument;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * u3-reacceptance, Passo 15 (Build and Test loop-back 1; tests written after the code, it is infrastructure; NFR3.2,
 * NFR3.4, NFR2.5): the warm-up on a pool of its own over the real PostgreSQL of the tests, with the application login.
 * After it, every connection of the pool holds the lock query prepared on the server with the exact text the lookup
 * sends; the timer of the lock check has no sample of it; a database failure never stops the start.
 */
@Tag("u3-reacceptance")
@ExtendWith(OutputCaptureExtension.class)
class TermsReacceptanceWarmupIT {

    private static final int POOL_SIZE = 4;

    private static final String PREPARED_LOCK_QUERIES =
            "select count(*) from pg_prepared_statements where statement like '%from terms_acceptance%'";

    private HikariDataSource pool;

    @BeforeEach
    void openPool() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(AbstractPostgresIT.jdbcUrl());
        config.setUsername(AbstractPostgresIT.APP.user());
        config.setPassword(AbstractPostgresIT.APP.password());
        config.setMaximumPoolSize(POOL_SIZE);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(2000);
        config.setPoolName("warmup-it-pool");
        pool = new HikariDataSource(config);
    }

    @AfterEach
    void closePool() {
        pool.close();
    }

    @Test
    void everyConnectionOfThePoolHoldsTheLockQueryPreparedOnTheServer() throws Exception {
        int warmed = new TermsReacceptanceWarmup(pool, new FixedTerms("v2")).warmUp();

        assertThat(warmed).isEqualTo(POOL_SIZE);
        List<Connection> held = borrowAll();
        try {
            assertThat(held)
                    .extracting(TermsReacceptanceWarmupIT::preparedLockQueries)
                    .hasSize(POOL_SIZE)
                    .allMatch(count -> count == 1L);
            assertThat(preparedStatementText(held.get(0)))
                    .as("the server-prepared text is the text of the lookup with its parameters numbered")
                    .isEqualTo(numbered(TermsReacceptanceWarmup.JDBC_SQL));
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
    }

    @Test
    void theWarmUpRecordsNoSampleInTheTimerOfTheLockCheck() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        JdbcTermsAcceptanceLookup lookup = new JdbcTermsAcceptanceLookup(JdbcClient.create(pool), registry);

        new TermsReacceptanceWarmup(pool, new FixedTerms("v2")).warmUp();

        assertThat(registry.get(JdbcTermsAcceptanceLookup.TIMER).timer().count())
                .isZero();
        assertThat(lookup.hasAccepted(UUID.randomUUID(), UUID.randomUUID(), "v2"))
                .isFalse();
        assertThat(registry.get(JdbcTermsAcceptanceLookup.TIMER).timer().count())
                .as("the timer keeps measuring the checks of the requests")
                .isEqualTo(1);
    }

    @Test
    void aDatabaseFailureBecomesOneWarningWithTheExceptionClassOnly(CapturedOutput output) {
        pool.close();

        int warmed = new TermsReacceptanceWarmup(pool, new FixedTerms("v2")).warmUp();

        assertThat(warmed).isZero();
        assertThat(output.getOut())
                .contains("WARN")
                .contains("Warm-up of the reacceptance lock query failed (SQLException)")
                .doesNotContain("has been closed");
        assertThat(output.getOut().split("Warm-up of the reacceptance lock query failed", -1))
                .hasSize(2);
    }

    @Test
    void theReadyEventWarmsThePoolAndLogsOnlyTheNumberOfConnections(CapturedOutput output) {
        TermsReacceptanceWarmup warmup = new TermsReacceptanceWarmup(pool, new FixedTerms("v2"));

        warmup.onReady();

        assertThat(output.getOut()).contains("Reacceptance lock query warmed on " + POOL_SIZE + " connections");
    }

    private List<Connection> borrowAll() throws SQLException {
        List<Connection> held = new ArrayList<>();
        for (int i = 0; i < POOL_SIZE; i++) {
            held.add(pool.getConnection());
        }
        return held;
    }

    private static long preparedLockQueries(Connection connection) {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(PREPARED_LOCK_QUERIES)) {
            result.next();
            return result.getLong(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("could not read pg_prepared_statements", failure);
        }
    }

    private static String preparedStatementText(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "select statement from pg_prepared_statements where statement like '%from terms_acceptance%'")) {
            result.next();
            return result.getString(1);
        }
    }

    /** pgjdbc sends each {@code ?} as {@code $1}, {@code $2}, ... in order. */
    private static String numbered(String jdbcSql) {
        StringBuilder text = new StringBuilder();
        int index = 0;
        for (char c : jdbcSql.toCharArray()) {
            if (c == '?') {
                text.append('$').append(++index);
            } else {
                text.append(c);
            }
        }
        return text.toString();
    }

    /** The catalog of the test: one current version, no history needed. */
    private record FixedTerms(String version) implements CurrentTerms {

        @Override
        public TermsDocument current() {
            return new TermsDocument(version, "Termos de teste.", "Aviso de risco de teste.");
        }

        @Override
        public Optional<TermsDocument> find(String wanted) {
            return version.equals(wanted) ? Optional.of(current()) : Optional.empty();
        }
    }
}
