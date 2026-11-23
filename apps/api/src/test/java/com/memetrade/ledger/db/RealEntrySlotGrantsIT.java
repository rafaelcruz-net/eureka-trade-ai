package com.memetrade.ledger.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 261121-paper-real-unico U2, Etapa 2 (written before the migration 0023; NFR3.4, NFR3.5): the application login reads
 * and inserts the slots and has no UPDATE, DELETE nor TRUNCATE on {@code real_entry_slot}; the retention login has
 * nothing on it; the owner itself lost TRUNCATE. Whatever the name of the application login (it comes from .env).
 */
class RealEntrySlotGrantsIT extends AbstractPostgresIT {

    @Test
    void theApplicationSelectsAndInserts() {
        JdbcClient app = connect(APP);
        String signature = SyntheticIds.signature();

        int inserted = app.sql("insert into real_entry_slot (tx_signature, slot, recorded_at) values (:s, 1, :at)")
                .param("s", signature)
                .param("at", Timestamp.from(Instant.parse("2026-11-22T10:00:00Z")))
                .update();

        assertThat(inserted).isEqualTo(1);
        assertThat(app.sql("select slot from real_entry_slot where tx_signature = :s")
                        .param("s", signature)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    void theApplicationHasExactlySelectAndInsert() {
        assertThat(privilege(APP.user(), "SELECT")).isTrue();
        assertThat(privilege(APP.user(), "INSERT")).isTrue();
        assertThat(privilege(APP.user(), "UPDATE")).isFalse();
        assertThat(privilege(APP.user(), "DELETE")).isFalse();
        assertThat(privilege(APP.user(), "TRUNCATE")).isFalse();
        assertThat(privilege(APP.user(), "REFERENCES")).isFalse();
        assertThat(privilege(APP.user(), "TRIGGER")).isFalse();
    }

    @Test
    void theRetentionLoginHasNothingAndTheOwnerLostTruncate() {
        for (String privilege : new String[] {"SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE"}) {
            assertThat(privilege(RETENTION.user(), privilege)).as(privilege).isFalse();
        }
        assertThat(privilege(OWNER.user(), "TRUNCATE")).isFalse();
        assertThat(connect(OWNER)
                        .sql("select count(*) from information_schema.role_table_grants"
                                + " where table_name = 'real_entry_slot' and grantee = 'PUBLIC'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private static boolean privilege(String user, String privilege) {
        return connect(OWNER)
                .sql("select has_table_privilege(:u, 'real_entry_slot', :p)")
                .param("u", user)
                .param("p", privilege)
                .query(Boolean.class)
                .single();
    }
}
