package com.memetrade.ledger.db;

import static com.memetrade.testsupport.LedgerRows.sqlState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 261121-paper-real-unico U2, Etapa 2 (written before the migration 0023; BR1.4, BR1.5, NFR3.4): the slot of a REAL
 * signature is recorded once and never changed. UPDATE, DELETE and TRUNCATE on {@code real_entry_slot} fail for the
 * owner of the schema too (the trigger {@code ledger_reject_change} of the ledger), and the row stays as it was.
 */
class RealEntrySlotAppendOnlyIT extends AbstractPostgresIT {

    private static final String DENIED = "42501";

    @Test
    void updateDeleteAndTruncateFailForTheOwnerAndTheRowStaysTheSame() {
        String signature = SyntheticIds.signature();
        insert(connect(APP), signature, 900L);
        JdbcClient owner = connect(OWNER);

        Throwable update =
                catchThrowable(() -> owner.sql("update real_entry_slot set slot = 901 where tx_signature = :s")
                        .param("s", signature)
                        .update());
        Throwable delete = catchThrowable(() -> owner.sql("delete from real_entry_slot where tx_signature = :s")
                .param("s", signature)
                .update());
        Throwable truncate =
                catchThrowable(() -> owner.sql("truncate real_entry_slot").update());

        assertThat(sqlState(update)).isEqualTo(DENIED);
        assertThat(sqlState(delete)).isEqualTo(DENIED);
        assertThat(sqlState(truncate)).isEqualTo(DENIED);
        assertThat(row(signature)).containsEntry("slot", 900L);
    }

    @Test
    void theApplicationLoginCannotChangeTheRowEither() {
        String signature = SyntheticIds.signature();
        JdbcClient app = connect(APP);
        insert(app, signature, 42L);

        assertThat(sqlState(catchThrowable(() -> app.sql("update real_entry_slot set slot = 43 where tx_signature = :s")
                        .param("s", signature)
                        .update())))
                .isEqualTo(DENIED);
        assertThat(sqlState(catchThrowable(() -> app.sql("delete from real_entry_slot where tx_signature = :s")
                        .param("s", signature)
                        .update())))
                .isEqualTo(DENIED);
        assertThat(sqlState(
                        catchThrowable(() -> app.sql("truncate real_entry_slot").update())))
                .isEqualTo(DENIED);
        assertThat(row(signature)).containsEntry("slot", 42L);
    }

    @Test
    void aSecondRowForTheSameSignatureIsRefusedAndANegativeSlotNever() {
        String signature = SyntheticIds.signature();
        JdbcClient app = connect(APP);
        insert(app, signature, 7L);

        assertThat(sqlState(catchThrowable(() -> insert(app, signature, 8L)))).isEqualTo("23505");
        assertThat(sqlState(catchThrowable(() -> insert(app, SyntheticIds.signature(), -1L))))
                .isEqualTo("23514");
        assertThat(row(signature)).containsEntry("slot", 7L);
    }

    private static void insert(JdbcClient client, String signature, long slot) {
        client.sql("insert into real_entry_slot (tx_signature, slot, recorded_at) values (:s, :slot, :at)")
                .param("s", signature)
                .param("slot", slot)
                .param("at", Timestamp.from(Instant.parse("2026-11-22T10:00:00Z")))
                .update();
    }

    private static Map<String, Object> row(String signature) {
        return connect(OWNER)
                .sql("select slot from real_entry_slot where tx_signature = :s")
                .param("s", signature)
                .query()
                .singleRow();
    }
}
