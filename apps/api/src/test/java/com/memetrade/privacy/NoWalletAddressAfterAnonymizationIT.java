package com.memetrade.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

import com.memetrade.audit.infra.AuditOutboxRelay;
import com.memetrade.botwallet.application.TxSignerCommandService;
import com.memetrade.botwallet.domain.CommandStore;
import com.memetrade.botwallet.domain.TxSignerPort;
import com.memetrade.shared.error.ApiException;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestWallet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Step 10 of the U5 of 261113-trading-real-bot, written BEFORE the code: the MANDATORY test of the team ("a anonimização
 * não deixa o endereço da carteira em nenhuma tabela nem log consultável"; BR6.2, S23, U5-NFR1.3), now with the bot
 * wallet. A person with a bot wallet, its consent, a withdrawal, a release and REAL events of the trail is anonymized;
 * until the TxSigner confirms the DEACTIVATE only {@code bot_wallet_account.public_address} still holds the address of
 * the bot wallet; after it, EVERY text and JSON column of EVERY table of the schema holds neither the main wallet nor the
 * bot wallet, and neither does the captured log. A wallet with a balance is refused (S22) and nothing changes.
 */
@Tag("u5-real-operations")
class NoWalletAddressAfterAnonymizationIT extends AbstractAnonymizationIT {

    @Autowired
    private AuditOutboxRelay relay;

    @Autowired
    private CommandStore commandStore;

    @Autowired
    private com.memetrade.botwallet.infra.RealAudit realAudit;

    @Autowired
    private java.time.Clock appClock;

    private record BotWallet(UUID botWalletId, String address) {}

    /** The bot wallet of the person and what the U1 to the U4 leave about it. */
    private BotWallet botWalletOf(Deletion deletion, boolean funded) {
        UUID botWalletId = UUID.randomUUID();
        String bot = TestWallet.generate().address();
        jdbc.sql(
                        "insert into bot_wallet_account (bot_wallet_id, tenant_id, user_id, public_address, main_wallet_address,"
                                + " network, created_at) values (:id, :t, :u, :bot, :main, 'LOCALNET', now())")
                .param("id", botWalletId)
                .param("t", deletion.tenantId())
                .param("u", deletion.userId())
                .param("bot", bot)
                .param("main", deletion.wallet())
                .update();
        jdbc.sql("insert into real_preferences (bot_wallet_id, cap_lamports, fee_reserve_lamports,"
                        + " max_withdrawal_fee_lamports, updated_at) values (:id, 1000, 0, 0, now())")
                .param("id", botWalletId)
                .update();
        jdbc.sql("insert into consent_challenge (challenge_id, tenant_id, user_id, kind, message, nonce, mode,"
                        + " cap_lamports, issued_at, expires_at, state, consumed_at) values (gen_random_uuid(), :t, :u,"
                        + " 'ACTIVATE', :message, :nonce, 'SIGNED', 1000, now(), now() + interval '5 minutes',"
                        + " 'CONSUMED', now())")
                .param("t", deletion.tenantId())
                .param("u", deletion.userId())
                .param(
                        "message",
                        "MemeTrade REAL\nCarteira principal: " + deletion.wallet() + "\nCarteira do bot: " + bot)
                .param("nonce", SyntheticIds.signature().substring(0, 40))
                .update();
        jdbc.sql("insert into withdrawal (withdrawal_id, tenant_id, bot_wallet_id, kind, lamports, fee_lamports, state,"
                        + " idempotency_key, signing_request_id, signed_transaction, message_digest, tx_signature,"
                        + " next_attempt_at, requested_at, updated_at) values (gen_random_uuid(), :t, :w, 'TOTAL', 1000,"
                        + " 5000, 'CONFIRMED', gen_random_uuid(), gen_random_uuid(), :tx, :digest, :sig, now(), now(),"
                        + " now())")
                .param("t", deletion.tenantId())
                .param("w", botWalletId)
                .param("tx", deletion.wallet() + bot)
                .param("digest", "0".repeat(64))
                .param("sig", SyntheticIds.signature())
                .update();
        jdbc.sql(
                        "insert into real_release (release_id, user_id, network, requested_by, requested_at, effective_at, state)"
                                + " values (gen_random_uuid(), :u, 'LOCALNET', :u, :at, :effective, 'ACTIVE')")
                .param("u", deletion.userId())
                .param("at", Timestamp.from(appClock.instant().minusSeconds(90_000)))
                .param("effective", Timestamp.from(appClock.instant().minusSeconds(90_000 - 86_400)))
                .update();
        if (funded) {
            jdbc.sql(
                            "insert into real_ledger_entry (entry_id, tenant_id, bot_wallet_id, entry_kind, lamports, reference,"
                                    + " idempotency_key, created_at) values (gen_random_uuid(), :t, :w, 'DEPOSIT', 1000000, :r,"
                                    + " :r, now())")
                    .param("t", deletion.tenantId())
                    .param("w", botWalletId)
                    .param("r", "dep:" + UUID.randomUUID())
                    .update();
        }
        transaction()
                .executeWithoutResult(status -> realAudit.system(
                        deletion.tenantId(),
                        "REAL_BOT_WALLET_CREATED",
                        "bot-wallet:" + botWalletId,
                        Map.of(),
                        com.memetrade.audit.api.RealReferences.botWallet(bot)));
        return new BotWallet(botWalletId, bot);
    }

    @Test
    void neitherAddressIsInAnyTableNorInTheLogAfterTheAnonymizationAndTheDeactivate() throws Exception {
        CapturedLogs logs = new CapturedLogs();
        try (logs) {
            Deletion deletion = deletionInProgress();
            BotWallet bot = botWalletOf(deletion, false);
            relayAll();
            assertThat(placesHolding(bot.address()))
                    .as("the scan finds the bot wallet before the anonymization")
                    .contains("bot_wallet_account.public_address", "audit_event.bot_wallet_address");

            anonymize(deletion);
            relayAll();

            assertThat(placesHolding(deletion.wallet()))
                    .as("places holding the main wallet")
                    .isEmpty();
            assertThat(placesHolding(bot.address()))
                    .as("until the DEACTIVATE is confirmed, only the address of the bot wallet itself")
                    .containsExactly("bot_wallet_account.public_address");

            new TxSignerCommandService(commandStore, mock(TxSignerPort.class), appClock).resendPending();

            assertThat(placesHolding(bot.address()))
                    .as("places holding the bot wallet")
                    .isEmpty();
            assertThat(logs.text()).doesNotContain(deletion.wallet()).doesNotContain(bot.address());
        }
    }

    /** S22: a bot wallet with a balance: 409 BALANCE with the explanation, and the account stays as it was. */
    @Test
    void aBotWalletWithABalanceRefusesTheAnonymizationAndNothingChanges() throws Exception {
        Deletion deletion = deletionInProgress();
        BotWallet bot = botWalletOf(deletion, true);

        Throwable refused = catchThrowable(() -> anonymize(deletion));

        assertThat(refused).isInstanceOfSatisfying(ApiException.class, problem -> {
            assertThat(problem.code().code()).isEqualTo("BALANCE");
            assertThat(problem.code().status()).isEqualTo(409);
            assertThat(problem.getMessage()).contains("Withdraw");
        });
        assertThat(accountStatus(deletion.userId())).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("select status from bot_wallet_account where bot_wallet_id = :w")
                        .param("w", bot.botWalletId())
                        .query(String.class)
                        .single())
                .isEqualTo("ACTIVE");
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
