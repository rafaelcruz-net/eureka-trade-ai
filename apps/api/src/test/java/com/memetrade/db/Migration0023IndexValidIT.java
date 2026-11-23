package com.memetrade.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.testsupport.AbstractPostgresIT;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U2, Etapa 2 (written before the migration 0023; NFR1.18, infrastructure-specification.md
 * "Provas do índice"): after the migration the index {@code real_ledger_entry_wallet_created_idx}, created
 * CONCURRENTLY, is valid and ready on {@code (bot_wallet_id, created_at)} of {@code real_ledger_entry}. A CONCURRENTLY
 * build that failed would leave it INVALID and the planner would silently skip it.
 */
class Migration0023IndexValidIT extends AbstractPostgresIT {

    @Test
    void theIndexIsValidAndReady() {
        Map<String, Object> index = connect(OWNER).sql("""
                        select i.indisvalid, i.indisready, pg_get_indexdef(i.indexrelid) as definition
                        from pg_index i
                        where i.indexrelid = to_regclass('real_ledger_entry_wallet_created_idx')""").query().singleRow();

        assertThat(index).containsEntry("indisvalid", true).containsEntry("indisready", true);
        assertThat((String) index.get("definition"))
                .contains("ON public.real_ledger_entry")
                .contains("(bot_wallet_id, created_at)");
    }

    @Test
    void theChangesetOfTheIndexIsRecordedOnce() {
        assertThat(connect(OWNER)
                        .sql("select count(*) from databasechangelog"
                                + " where id = '0023-real-ledger-entry-wallet-created-idx'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }
}
