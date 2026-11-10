package com.memetrade.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestWalletHmac;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * u2-retention (BR2.18, BR2.14, Passo 6): the relay copies the {@code walletHmac} of the outbox payload to
 * {@code audit_event.wallet_hmac}; a payload without the field (older events, events without a wallet) leaves the column
 * empty, and an older payload is relayed as it is, never rewritten.
 */
@Tag("u2-retention")
class AuditOutboxRelayIT extends AbstractPostgresIT {

    @Autowired
    private AuditOutboxRelay relay;

    @Test
    void theHmacOfThePayloadGoesToItsColumn() {
        String hmac = TestWalletHmac.of(SyntheticIds.mint());
        UUID id = outbox(AuditPayloadCodec.encode(event("RELAY_IT_WALLET"), Optional.of(hmac)));

        relayAll();

        assertThat(walletHmacOf(id)).isEqualTo(hmac);
    }

    @Test
    void anOlderPayloadWithoutTheFieldIsRelayedWithAnEmptyColumn() {
        AuditEventCommand older = event("RELAY_IT_OLDER");
        UUID id = outbox(AuditPayloadCodec.encode(older));

        relayAll();

        assertThat(walletHmacOf(id)).isNull();
        assertThat(jdbc.sql("select action from audit_event where event_id = :id")
                        .param("id", id)
                        .query(String.class)
                        .single())
                .isEqualTo("RELAY_IT_OLDER");
    }

    @Test
    void aRepeatedDeliveryKeepsTheFirstCopyAndItsHmac() {
        String hmac = TestWalletHmac.of(SyntheticIds.mint());
        AuditEventCommand event = event("RELAY_IT_REPEATED");
        UUID id = outbox(AuditPayloadCodec.encode(event, Optional.of(hmac)));
        relayAll();
        jdbc.sql("update audit_outbox set delivered_at = null where event_id = :id")
                .param("id", id)
                .update();

        relayAll();

        assertThat(jdbc.sql("select count(*) from audit_event where event_id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(walletHmacOf(id)).isEqualTo(hmac);
    }

    private AuditEventCommand event(String action) {
        return AuditEventCommand.system(action, null, UUID.randomUUID().toString(), clock.instant(), Map.of());
    }

    private UUID outbox(String payload) {
        UUID id = UUID.fromString(
                payload.substring(payload.indexOf("\"eventId\":\"") + 11, payload.indexOf("\"eventId\":\"") + 47));
        jdbc.sql("""
                        insert into audit_outbox (event_id, payload, created_at, delivered_at)
                        values (:id, cast(:payload as jsonb), :created, null)
                        """)
                .param("id", id)
                .param("payload", payload)
                .param("created", Timestamp.from(clock.instant()))
                .update();
        return id;
    }

    private void relayAll() {
        for (int batch = 0; batch < 1000; batch++) {
            if (relay.relayBatch() == 0) {
                return;
            }
        }
        throw new IllegalStateException("The audit outbox still has events after 1000 batches");
    }

    /** The column of the relayed event; null when it is empty (the row itself must exist). */
    private String walletHmacOf(UUID id) {
        java.util.List<String> values = new java.util.ArrayList<>();
        jdbc.sql("select wallet_hmac from audit_event where event_id = :id")
                .param("id", id)
                .query((rs, row) -> values.add(rs.getString("wallet_hmac")))
                .list();
        assertThat(values).as("relayed rows of %s", id).hasSize(1);
        return values.getFirst();
    }
}
