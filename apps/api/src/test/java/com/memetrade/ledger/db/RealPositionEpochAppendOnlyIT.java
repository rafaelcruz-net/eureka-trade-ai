package com.memetrade.ledger.db;

import static com.memetrade.testsupport.LedgerRows.sqlState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 261121-paper-real-unico U2a, Etapa 14b (written before the migration 0025; decision of the founder of 2026-11-22,
 * NFR3.4, NFR3.5): the marks of zero of {@code real_position_epoch} are only inserted. The application login selects and
 * inserts; UPDATE, DELETE and TRUNCATE fail for it and for the owner of the schema (the trigger
 * {@code ledger_reject_change} of the ledger), and the mark stays as it was. A mark keeps its whole key of zero or none
 * of it, never a negative count of fills, and only the reasons of the plan.
 */
class RealPositionEpochAppendOnlyIT extends AbstractPostgresIT {

    private static final String DENIED = "42501";
    private static final String CHECK = "23514";

    @Test
    void theApplicationInsertsAndReadsAMarkAndNobodyChangesIt() {
        JdbcClient app = connect(APP);
        UUID tenant =
                TestAccounts.betaUser(app, TestWallet.generate().address()).tenantId();
        UUID wallet = UUID.randomUUID();
        insert(app, tenant, wallet, "ZERO", 7L, 3L);
        JdbcClient owner = connect(OWNER);

        for (JdbcClient client : new JdbcClient[] {app, owner}) {
            assertThat(sqlState(catchThrowable(() -> client.sql(
                                    "update real_position_epoch set fills_through = 9 where bot_wallet_id = :w")
                            .param("w", wallet)
                            .update())))
                    .isEqualTo(DENIED);
            assertThat(sqlState(
                            catchThrowable(() -> client.sql("delete from real_position_epoch where bot_wallet_id = :w")
                                    .param("w", wallet)
                                    .update())))
                    .isEqualTo(DENIED);
            assertThat(sqlState(catchThrowable(
                            () -> client.sql("truncate real_position_epoch").update())))
                    .isEqualTo(DENIED);
        }
        assertThat(app.sql("select fills_through from real_position_epoch where bot_wallet_id = :w")
                        .param("w", wallet)
                        .query(Long.class)
                        .single())
                .isEqualTo(3L);
    }

    @Test
    void aPartialKeyANegativeCountAndAnUnknownReasonAreRefused() {
        JdbcClient app = connect(APP);
        UUID tenant =
                TestAccounts.betaUser(app, TestWallet.generate().address()).tenantId();
        UUID wallet = UUID.randomUUID();

        assertThat(sqlState(catchThrowable(() -> insert(app, tenant, wallet, "WHATEVER", 7L, 3L))))
                .isEqualTo(CHECK);
        assertThat(sqlState(catchThrowable(() -> insert(app, tenant, wallet, "ZERO", 7L, -1L))))
                .isEqualTo(CHECK);
        assertThat(sqlState(catchThrowable(() -> app.sql("""
                                insert into real_position_epoch (tenant_id, bot_wallet_id, mint, zero_slot, fills_through,
                                  window_from, reason, recorded_at)
                                values (:t, :w, 'XYZ', 5, 0, :at, 'ZERO', :at)""")
                        .param("t", tenant)
                        .param("w", wallet)
                        .param("at", Timestamp.from(Instant.parse("2026-11-22T10:00:00Z")))
                        .update())))
                .isEqualTo(CHECK);
        assertThat(sqlState(catchThrowable(() -> app.sql("""
                                insert into real_position_epoch (tenant_id, bot_wallet_id, mint, fills_through,
                                  window_from, reason, recorded_at)
                                values (:t, :w, 'XYZ', 2, :at, 'OUT_OF_ORDER', :at)""")
                        .param("t", tenant)
                        .param("w", wallet)
                        .param("at", Timestamp.from(Instant.parse("2026-11-22T10:00:00Z")))
                        .update())))
                .as("a mark without a zero counts no fill")
                .isEqualTo(CHECK);
    }

    @Test
    void aFullReadMarkWithoutAZeroIsAccepted() {
        JdbcClient app = connect(APP);
        UUID tenant =
                TestAccounts.betaUser(app, TestWallet.generate().address()).tenantId();

        int inserted = app.sql("""
                        insert into real_position_epoch (tenant_id, bot_wallet_id, mint, fills_through, window_from,
                          reason, recorded_at)
                        values (:t, :w, 'XYZ', 0, :at, 'OUT_OF_ORDER', :at)""")
                .param("t", tenant)
                .param("w", UUID.randomUUID())
                .param("at", Timestamp.from(Instant.parse("2026-11-22T10:00:00Z")))
                .update();

        assertThat(inserted).isEqualTo(1);
    }

    private static void insert(JdbcClient client, UUID tenant, UUID wallet, String reason, long slot, long fills) {
        Instant at = Instant.parse("2026-11-22T10:00:00Z");
        client.sql("""
                        insert into real_position_epoch (tenant_id, bot_wallet_id, mint, zero_slot, zero_inherited,
                          zero_tx_signature, zero_created_at, zero_reference, zero_kind_rank, zero_idempotency_key,
                          fills_through, window_from, reason, recorded_at)
                        values (:t, :w, 'XYZ', :slot, false, :sig, :at, 'order:1', 0, 'order:1:OUT', :fills, :at,
                          :reason, :at)""")
                .param("t", tenant)
                .param("w", wallet)
                .param("slot", slot)
                .param("sig", SyntheticIds.signature())
                .param("at", Timestamp.from(at))
                .param("fills", fills)
                .param("reason", reason)
                .update();
    }
}
