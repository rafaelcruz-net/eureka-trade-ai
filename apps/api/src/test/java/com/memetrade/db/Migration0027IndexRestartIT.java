package com.memetrade.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.memetrade.testsupport.AbstractPostgresIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 261121-paper-real-unico U4, Etapa 7 (written before the migration 0027; NFR1.12, NFR2.7; performance-design.md
 * "Índices: decisão"): in the pattern of {@code Migration0023IndexRestartIT}, a CREATE INDEX CONCURRENTLY interrupted in
 * the middle leaves an INVALID index and no row of its changeset; the next run removes the leftover and builds it valid,
 * recorded once. The NOT VALID-then-VALIDATE rule of the 0026 is not touched.
 */
class Migration0027IndexRestartIT extends AbstractPostgresIT {

    private static final String INDEX = "real_decision_request_bot_pending_sell_idx";
    private static final String CHANGESET = "0027-real-decision-request-bot-pending-sell-idx";

    @Test
    void anInvalidIndexLeftByAnInterruptedRunIsRebuiltValidOnTheNextRun() {
        String dbName = "u4idx_" + UUID.randomUUID().toString().replace("-", "");
        connect(SUPERUSER).sql("create database " + dbName).update();
        String url = POSTGRES.getJdbcUrl().replace("/memetrade", "/" + dbName);
        bootstrap(dbName);
        migrate(url);
        JdbcClient superuser = connect(SUPERUSER, url);
        superuser
                .sql("update pg_index set indisvalid = false where indexrelid = to_regclass(:i)")
                .param("i", INDEX)
                .update();
        superuser
                .sql("delete from databasechangelog where id = :c")
                .param("c", CHANGESET)
                .update();
        assertThat(valid(superuser)).isFalse();

        assertThatCode(() -> migrate(url)).doesNotThrowAnyException();

        assertThat(valid(superuser)).isTrue();
        assertThat(superuser
                        .sql("select count(*) from databasechangelog where id = :c")
                        .param("c", CHANGESET)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(superuser
                        .sql("select indexdef from pg_indexes where indexname = :i")
                        .param("i", INDEX)
                        .query(String.class)
                        .single())
                .contains("(bot_id)")
                .contains("'PENDING'")
                .contains("'SELL'");
        assertThat(superuser
                        .sql("select convalidated from pg_constraint where conname = 'bot_cycle_outcome_ck'")
                        .query(Boolean.class)
                        .single())
                .as("the rule of the 0026 is untouched")
                .isTrue();
    }

    private static boolean valid(JdbcClient client) {
        return client.sql("select indisvalid and indisready from pg_index where indexrelid = to_regclass(:i)")
                .param("i", INDEX)
                .query(Boolean.class)
                .single();
    }
}
