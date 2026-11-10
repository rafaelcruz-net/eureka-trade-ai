package com.memetrade.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.infra.AuditOutboxRelay;
import com.memetrade.identity.api.Principal;
import com.memetrade.pnl.application.ReconciliationService;
import com.memetrade.testsupport.LedgerRows;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 2 of u5-anonymization, written BEFORE the code (NFR5.1; BR5.3; AC6.2.3): the anonymization never changes nor
 * deletes the PAPER orders, the fills, the ledger entries, the refusals of the barrier, the acceptances of the terms and
 * the trail of the person; the reconciliation of the ledger gives the same answer before and after (no divergence
 * appears), and the open PAPER account keeps its state without any new closing order.
 */
class AnonymizationKeepsHistoryIT extends AbstractAnonymizationIT {

    private static final String[] OWNED = {"paper_order", "fill", "ledger_entry", "order_rejection", "paper_account"};

    @Autowired
    private ReconciliationService reconciliation;

    @Autowired
    private AuditOutboxRelay relay;

    @Test
    void theHistoryOfThePersonIsIdenticalBeforeAndAfter() throws Exception {
        Deletion deletion = deletionInProgress();
        Principal person = deletion.guest().principal();
        LedgerRows ledger = new LedgerRows(jdbc);
        UUID bot = ledger.account(person, "OPEN");
        UUID order = ledger.order(person, bot, "CLOSE", "FILLED", null, "key-" + UUID.randomUUID());
        ledger.fill(person, bot, order);
        ledger.order(person, bot, "CLOSE", "NOT_FILLED", "NO_LIQUIDITY", "key-" + UUID.randomUUID());
        relayAll();
        Map<String, String> before = snapshot(person.userId());

        anonymize(deletion);
        relayAll();

        Map<String, String> after = snapshot(person.userId());
        for (String table : OWNED) {
            assertThat(after.get(table)).as(table).isEqualTo(before.get(table));
        }
        assertThat(after.get("terms_acceptance")).isEqualTo(before.get("terms_acceptance"));
        assertThat(after.get("trail"))
                .as("every earlier event of the trail is still there, unchanged")
                .isEqualTo(before.get("trail"));
        assertThat(jdbc.sql("select count(*) from paper_order where bot_id = :b")
                        .param("b", bot)
                        .query(Long.class)
                        .single())
                .as("no closing order is created for the open account")
                .isEqualTo(2);
    }

    @Test
    void theReconciliationOfTheLedgerGivesTheSameAnswerAfterTheAnonymization() throws Exception {
        Deletion deletion = deletionInProgress();
        Principal person = deletion.guest().principal();
        LedgerRows ledger = new LedgerRows(jdbc);
        ledger.account(person, "OPEN");
        ReconciliationService.Result before =
                reconciliation.check(person.tenantId(), person.userId(), ReconciliationService.Trigger.ON_DEMAND);

        anonymize(deletion);

        ReconciliationService.Result after =
                reconciliation.check(person.tenantId(), person.userId(), ReconciliationService.Trigger.ON_DEMAND);
        assertThat(after.divergences()).isEqualTo(before.divergences());
        assertThat(after.reconciled()).isEqualTo(before.reconciled());
    }

    @Test
    void theAcceptancesOfTheTermsStayLinkedToTheInternalIdentifier() throws Exception {
        Deletion deletion = deletionInProgress();
        long acceptances = jdbc.sql("select count(*) from terms_acceptance where user_id = :u")
                .param("u", deletion.userId())
                .query(Long.class)
                .single();

        anonymize(deletion);

        assertThat(jdbc.sql("select count(*) from terms_acceptance where user_id = :u")
                        .param("u", deletion.userId())
                        .query(Long.class)
                        .single())
                .isEqualTo(acceptances)
                .isPositive();
    }

    private void relayAll() {
        while (relay.relayBatch() > 0) {
            // deliver every pending event to the trail
        }
    }

    private Map<String, String> snapshot(UUID userId) {
        Map<String, String> digests = new LinkedHashMap<>();
        for (String table : OWNED) {
            digests.put(
                    table,
                    digest(
                            "select coalesce(md5(string_agg(t::text, '|' order by t::text)), '-') from " + table
                                    + " t where t.owner_user_id = :u",
                            userId));
        }
        digests.put("terms_acceptance", digest("""
                select coalesce(md5(string_agg(t::text, '|' order by t::text)), '-') from terms_acceptance t
                where t.user_id = :u
                """, userId));
        digests.put("trail", digest("""
                select coalesce(md5(string_agg(t::text, '|' order by t::text)), '-') from audit_event t
                where (t.target_ref = 'user:' || cast(:u as text) or t.actor = cast(:u as text))
                  and t.action not in ('ACCOUNT_ANONYMIZED', 'DATA_REQUEST_TRANSITIONED')
                """, userId));
        return digests;
    }

    private String digest(String sql, UUID userId) {
        return jdbc.sql(sql).param("u", userId).query(String.class).single();
    }
}
