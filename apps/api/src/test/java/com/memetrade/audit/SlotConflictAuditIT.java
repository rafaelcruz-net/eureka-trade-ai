package com.memetrade.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.infra.AuditOutboxRelay;
import com.memetrade.audit.infra.WalletHasher;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.ledger.api.RecordSlotResult;
import com.memetrade.ledger.api.SlotWriter;
import com.memetrade.shared.web.CorrelationId;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import com.memetrade.testsupport.TestWalletHmac;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 261121-paper-real-unico U2, Etapa 4 (written before the code; NFR3.9, NFR3.13, D-NFR2, security-design.md): the
 * event REAL_SLOT_CONFLICT keeps the signature only abbreviated (4 … 4) and as its HMAC with the domain label
 * {@code "real-tx-signature:"}; the full signature is in no column of {@code audit_outbox} nor of {@code audit_event};
 * the event carries the correlation id of the confirmation that refused the slot.
 */
class SlotConflictAuditIT extends AbstractPostgresIT {

    @Autowired
    private RealLedger ledger;

    @Autowired
    private AuditOutboxRelay relay;

    @Autowired
    private PlatformTransactionManager manager;

    @Test
    void theConflictIsAuditedAbbreviatedAndAsAnHmacWithTheCorrelationIdOfTheConfirmation() {
        TestAccounts.Seeded person =
                TestAccounts.betaUser(jdbc, TestWallet.generate().address());
        String signature = SyntheticIds.signature();
        String correlationId = UUID.randomUUID().toString();
        TransactionTemplate transactions = new TransactionTemplate(manager);
        transactions.execute(s -> ledger.recordSlot(person.tenantId(), signature, 77L, SlotWriter.WALLET_CONFIRMATION));

        MDC.put(CorrelationId.MDC_KEY, correlationId);
        RecordSlotResult result;
        try {
            result = transactions.execute(
                    s -> ledger.recordSlot(person.tenantId(), signature, 78L, SlotWriter.WALLET_CONFIRMATION));
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }

        assertThat(result).isEqualTo(RecordSlotResult.CONFLICT_REFUSED);
        Map<String, Object> outbox =
                jdbc.sql("""
                        select payload::text as payload,
                          payload->>'correlationId' as correlation,
                          payload->>'tenantId' as tenant,
                          payload->'details'->>'txSignatureAbbrev' as abbrev,
                          payload->'details'->>'txSignatureHmac' as hmac,
                          payload->'details'->>'recordedSlot' as recorded,
                          payload->'details'->>'refusedSlot' as refused,
                          payload->'details'->>'writer' as writer
                        from audit_outbox
                        where payload->>'action' = 'REAL_SLOT_CONFLICT' and payload->>'correlationId' = :c""").param("c", correlationId).query().singleRow();
        String expectedHmac = new WalletHasher(TestWalletHmac.testKey()).hmacOfTxSignature(signature);
        assertThat(outbox)
                .containsEntry("correlation", correlationId)
                .containsEntry("tenant", person.tenantId().toString())
                .containsEntry("abbrev", signature.substring(0, 4) + "…" + signature.substring(signature.length() - 4))
                .containsEntry("hmac", expectedHmac)
                .containsEntry("recorded", "77")
                .containsEntry("refused", "78")
                .containsEntry("writer", "WALLET_CONFIRMATION");
        assertThat((String) outbox.get("payload")).doesNotContain(signature);

        relayEverything();

        List<Map<String, Object>> events =
                jdbc.sql("""
                        select row_to_json(e)::text as row, e.tx_signature, e.wallet_hmac, e.bot_wallet_address
                        from audit_event e
                        where e.action = 'REAL_SLOT_CONFLICT' and e.correlation_id = :c""").param("c", correlationId).query().listOfRows();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("tx_signature")).isNull();
        assertThat(events.getFirst().get("wallet_hmac")).isNull();
        assertThat(events.getFirst().get("bot_wallet_address")).isNull();
        assertThat((String) events.getFirst().get("row"))
                .doesNotContain(signature)
                .contains(expectedHmac);
    }

    private void relayEverything() {
        int batches = 0;
        while (relay.relayBatch() > 0) {
            batches++;
            assertThat(batches).as("the outbox drains").isLessThan(1_000);
        }
    }
}
