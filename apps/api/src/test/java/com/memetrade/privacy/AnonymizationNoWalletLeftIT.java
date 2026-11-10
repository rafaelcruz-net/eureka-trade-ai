package com.memetrade.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.infra.AuditOutboxRelay;
import com.memetrade.identity.api.Principal;
import com.memetrade.testsupport.LedgerRows;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Passo 2 of u5-anonymization, written BEFORE the code: the MANDATORY test of the team ("a anonimização não deixa o
 * endereço da carteira em nenhuma tabela nem log consultável"; BR5.4, NFR1.1). A person signs in with a wallet generated
 * in test code, gets bots, comments, PAPER orders, events of the trail and a DELETION request; after the anonymization,
 * EVERY {@code text}, {@code varchar}, {@code char} and {@code jsonb} column of EVERY table of the application schema
 * (the trail, the outbox payload and the requests included) and the captured log are searched for the full address. A
 * finding names the table and the column, never the value.
 */
class AnonymizationNoWalletLeftIT extends AbstractAnonymizationIT {

    @Autowired
    private AuditOutboxRelay relay;

    @Test
    void theFullAddressIsInNoTableAndInNoLogAfterTheAnonymization() throws Exception {
        CapturedLogs logs = new CapturedLogs();
        try (logs) {
            run(logs);
        }
    }

    private void run(CapturedLogs logs) throws Exception {
        Deletion deletion = deletionInProgress();
        Principal person = deletion.guest().principal();
        String address = deletion.wallet();
        seedBot(person, "ACTIVE");
        seedBot(person, "DRAFT");
        seedFeedback(person, 2);
        LedgerRows ledger = new LedgerRows(jdbc);
        UUID bot = ledger.account(person, "OPEN");
        ledger.order(person, bot, "CLOSE", "FILLED", null, "key-" + UUID.randomUUID());
        // A second login of the same wallet leaves another challenge, session and stored challenge answer.
        relogin(deletion.guest());
        relayAll();
        assertThat(placesHolding(address))
                .as("the scan finds the address before the anonymization (it scans the right places)")
                .contains("wallet.address", "login_challenge.wallet_address");

        anonymize(deletion);
        relayAll();

        assertThat(placesHolding(address))
                .as("tables and columns still holding the full address")
                .isEmpty();
        assertThat(logs.size()).as("the log of the flow was captured").isPositive();
        assertThat(logs.text().contains(address))
                .as("the captured log holds the full address")
                .isFalse();
    }

    @Test
    void theAddressOfAnotherPersonIsStillWhereItWas() throws Exception {
        Deletion deletion = deletionInProgress();
        WalletGuest other = newWalletGuest();

        anonymize(deletion);

        assertThat(placesHolding(other.wallet().address())).contains("wallet.address");
    }

    private void relayAll() {
        while (relay.relayBatch() > 0) {
            // deliver every pending event to the trail
        }
    }

    /** Every {@code table.column} of the public schema whose text holds {@code needle}, read as the superuser. */
    private List<String> placesHolding(String needle) {
        JdbcClient superuser = connect(SUPERUSER);
        List<Map<String, Object>> columns = superuser.sql("""
                        select c.table_name, c.column_name
                        from information_schema.columns c
                        join information_schema.tables t
                          on t.table_schema = c.table_schema and t.table_name = c.table_name
                        where c.table_schema = 'public' and t.table_type = 'BASE TABLE'
                          and c.data_type in ('text', 'character varying', 'character', 'jsonb', 'json')
                        order by c.table_name, c.column_name
                        """).query().listOfRows();
        assertThat(columns).as("the catalog lists the text columns").hasSizeGreaterThan(20);
        List<String> found = new ArrayList<>();
        for (Map<String, Object> column : columns) {
            String table = (String) column.get("table_name");
            String name = (String) column.get("column_name");
            long hits = superuser
                    .sql("select count(*) from public.\"" + table + "\" where position(:needle in \"" + name
                            + "\"::text) > 0")
                    .param("needle", needle)
                    .query(Long.class)
                    .single();
            if (hits > 0) {
                found.add(table + "." + name);
            }
        }
        return found;
    }
}
