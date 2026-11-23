package com.memetrade.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.memetrade.testsupport.AbstractPostgresIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 261121-paper-real-unico U2, Etapa 2 (written before the migration 0023; NFR1.18, infrastructure-specification.md
 * "Retomada depois de falha"): a CREATE INDEX CONCURRENTLY interrupted in the middle leaves an INVALID index and no row
 * of its changeset. On a database of its own the test reproduces that state with the superuser and runs the db-migrate
 * entry point again: it exits 0, the {@code DROP INDEX CONCURRENTLY IF EXISTS} at the start of the changeset removes
 * the leftover, the index is built again valid, and the changeset is recorded once.
 */
class Migration0023IndexRestartIT extends AbstractPostgresIT {

    private static final String INDEX = "real_ledger_entry_wallet_created_idx";
    private static final String CHANGESET = "0023-real-ledger-entry-wallet-created-idx";

    @Test
    void anInvalidIndexLeftByAnInterruptedRunIsRebuiltValidOnTheNextRun() {
        String dbName = "u2idx_" + UUID.randomUUID().toString().replace("-", "");
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
                        .sql("select count(*) from pg_indexes where indexname = :i")
                        .param("i", INDEX)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    private static boolean valid(JdbcClient client) {
        return client.sql("select indisvalid and indisready from pg_index where indexrelid = to_regclass(:i)")
                .param("i", INDEX)
                .query(Boolean.class)
                .single();
    }
}
