package com.memetrade.botwallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.infra.AuditOutboxRelay;
import com.memetrade.botwallet.api.RealAnonymization;
import com.memetrade.botwallet.api.RealAnonymization.CloseOutcome;
import com.memetrade.botwallet.application.TxSignerCommandService;
import com.memetrade.testsupport.SyntheticIds;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 10 of the U5, written BEFORE the code (F9, BR6.1, BR6.2, S22, S23, U5-NFR1.3): the M1
 * {@code closeForAnonymization}. With a balance, a position, an order or a withdrawal in progress the anonymization is
 * refused (withdraw first) and nothing changes. Otherwise, in the transaction of the anonymization: the wallet is CLOSED,
 * the command DEACTIVATE (only the wallet id) is recorded, the main wallet, the consent message and the transactions of
 * the withdrawals are cleared, the releases are revoked, and the trail and the outbox lose the address of the bot wallet
 * AFTER the closing events; once the TxSigner confirms the DEACTIVATE the address of the bot wallet leaves too.
 */
@Tag("u5-real-operations")
class BotWalletAnonymizationIT extends AbstractRealOpsIT {

    @Autowired
    private RealAnonymization anonymization;

    @Autowired
    private TxSignerCommandService commands;

    @Autowired
    private AuditOutboxRelay relay;

    private CloseOutcome close(OpsPerson person) {
        return transactions.execute(status -> anonymization.closeForAnonymization(person.tenantId(), person.userId()));
    }

    private String walletStatus(OpsPerson person) {
        return jdbc.sql("select status from bot_wallet_account where bot_wallet_id = :w")
                .param("w", person.botWalletId())
                .query(String.class)
                .single();
    }

    private void ledger(OpsPerson person, String kind, long lamports, String mint, Long units, String reference) {
        jdbc.sql("insert into real_ledger_entry (entry_id, tenant_id, bot_wallet_id, entry_kind, lamports, mint,"
                        + " token_base_units, reference, idempotency_key, created_at) values (gen_random_uuid(), :t, :w,"
                        + " :kind, :lamports, :mint, :units, :ref, gen_random_uuid()::text, now())")
                .param("t", person.tenantId())
                .param("w", person.botWalletId())
                .param("kind", kind)
                .param("lamports", lamports)
                .param("mint", mint)
                .param("units", units, java.sql.Types.NUMERIC)
                .param("ref", reference)
                .update();
    }

    /** S22: a wallet with a balance: refused BALANCE, and nothing changed. */
    @Test
    void aWalletWithABalanceIsRefusedBalanceAndNothingChanges() throws Exception {
        OpsPerson person = activePerson();
        deposit(person, 1_000_000L);

        CloseOutcome outcome = close(person);

        assertThat(outcome).isEqualTo(new CloseOutcome.Refused("BALANCE"));
        assertThat(walletStatus(person)).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("select count(*) from real_pending_txsigner_command where bot_wallet_id = :w")
                        .param("w", person.botWalletId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void aWalletWithAPositionIsRefusedPosition() throws Exception {
        OpsPerson person = activePerson();
        deposit(person, 50_000_000L);
        ledger(person, "SWAP_FILL_IN", -50_000_000L, SyntheticIds.mint(), 35_000L, "order:" + UUID.randomUUID());

        assertThat(close(person)).isEqualTo(new CloseOutcome.Refused("POSITION"));
    }

    @Test
    void anOrderOrAWithdrawalInProgressIsRefusedOrderInProgress() throws Exception {
        OpsPerson withOrder = activePerson();
        UUID quoted = order(withOrder, "QUOTED");
        ledger(withOrder, "RESERVE", 50_000_000L, null, null, "order:" + quoted);
        OpsPerson withWithdrawal = activePerson();
        jdbc.sql("insert into withdrawal (withdrawal_id, tenant_id, bot_wallet_id, kind, lamports, fee_lamports, state,"
                        + " idempotency_key, signing_request_id, next_attempt_at, requested_at, updated_at) values"
                        + " (gen_random_uuid(), :t, :w, 'TOTAL', 1000, 5000, 'REQUESTED', gen_random_uuid(),"
                        + " gen_random_uuid(), now(), now(), now())")
                .param("t", withWithdrawal.tenantId())
                .param("w", withWithdrawal.botWalletId())
                .update();

        assertThat(close(withOrder)).isEqualTo(new CloseOutcome.Refused("ORDER_IN_PROGRESS"));
        assertThat(close(withWithdrawal)).isEqualTo(new CloseOutcome.Refused("ORDER_IN_PROGRESS"));
    }

    /** S23: an empty wallet closes; addresses, messages and transactions are cleared; DEACTIVATE goes to the TxSigner. */
    @Test
    void anEmptyWalletClosesAndClearsTheAddressesTheMessagesAndTheTransactions() throws Exception {
        OpsPerson person = activePerson();
        UUID release = releaseOf(person.userId(), "LOCALNET", "ACTIVE", Duration.ofHours(30));
        jdbc.sql("insert into consent_challenge (challenge_id, tenant_id, user_id, kind, message, nonce, mode,"
                        + " cap_lamports, issued_at, expires_at, state, consumed_at) values (gen_random_uuid(), :t, :u,"
                        + " 'ACTIVATE', :message, :nonce, 'SIGNED', 1000, now(), now() + interval '5 minutes',"
                        + " 'CONSUMED', now())")
                .param("t", person.tenantId())
                .param("u", person.userId())
                .param("message", "Carteira principal: " + person.mainAddress())
                .param("nonce", SyntheticIds.signature().substring(0, 40))
                .update();
        jdbc.sql("insert into withdrawal (withdrawal_id, tenant_id, bot_wallet_id, kind, lamports, fee_lamports, state,"
                        + " idempotency_key, signing_request_id, signed_transaction, message_digest, tx_signature,"
                        + " next_attempt_at, requested_at, updated_at) values (gen_random_uuid(), :t, :w, 'TOTAL', 1000,"
                        + " 5000, 'CONFIRMED', gen_random_uuid(), gen_random_uuid(), :tx, :digest, :sig, now(), now(),"
                        + " now())")
                .param("t", person.tenantId())
                .param("w", person.botWalletId())
                .param("tx", "AQID" + person.mainAddress())
                .param("digest", "0".repeat(64))
                .param("sig", SyntheticIds.signature())
                .update();

        CloseOutcome outcome = close(person);

        assertThat(outcome).isEqualTo(new CloseOutcome.Closed(true));
        Map<String, Object> wallet = jdbc.sql("select status, main_wallet_address, public_address from"
                        + " bot_wallet_account where bot_wallet_id = :w")
                .param("w", person.botWalletId())
                .query()
                .singleRow();
        assertThat(wallet.get("status")).isEqualTo("CLOSED");
        assertThat(wallet.get("main_wallet_address")).isNull();
        assertThat(wallet.get("public_address")).isEqualTo(person.botAddress());
        assertThat(jdbc.sql("select count(*) from consent_challenge where user_id = :u and message is not null")
                        .param("u", person.userId())
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("select signed_transaction is null and message_digest is null and redacted_at is not null"
                                + " from withdrawal where bot_wallet_id = :w")
                        .param("w", person.botWalletId())
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(releaseState(release)).isEqualTo("REVOKED");
        assertThat(jdbc.sql("select kind || ':' || payload::text from real_pending_txsigner_command where"
                                + " bot_wallet_id = :w")
                        .param("w", person.botWalletId())
                        .query(String.class)
                        .single())
                .startsWith("DEACTIVATE:")
                .contains(person.botWalletId().toString())
                .doesNotContain(person.botAddress());

        commands.resendPending();

        assertThat(signer.closed).containsValue(person.botAddress());
        assertThat(jdbc.sql("select public_address from bot_wallet_account where bot_wallet_id = :w")
                        .param("w", person.botWalletId())
                        .query(String.class)
                        .optional())
                .isEmpty();
    }

    /** BR6.2, U5-NFR1.3: the trail and the outbox lose the address in the same transaction, after the closing events. */
    @Test
    void theTrailAndTheOutboxLoseTheBotAddressAndAPendingDeliveryReadsTheRedactedPayload() throws Exception {
        OpsPerson person = activePerson();
        transactions.executeWithoutResult(status -> audit().system(
                        person.tenantId(),
                        "REAL_DEPOSIT_RECORDED",
                        "bot-wallet:" + person.botWalletId(),
                        Map.of("lamports", "1"),
                        com.memetrade.audit.api.RealReferences.botWallet(person.botAddress())));
        while (relay.relayBatch() > 0) {
            // the older events of the trail are delivered first
        }
        transactions.executeWithoutResult(status -> audit().system(
                        person.tenantId(),
                        "REAL_DEPOSIT_RECORDED",
                        "bot-wallet:" + person.botWalletId(),
                        Map.of("lamports", "2"),
                        com.memetrade.audit.api.RealReferences.botWallet(person.botAddress())));

        close(person);
        while (relay.relayBatch() > 0) {
            // the pending event is delivered after the redaction
        }

        assertThat(jdbc.sql("select count(*) from audit_event where bot_wallet_address = :a")
                        .param("a", person.botAddress())
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from audit_outbox where position(:a in payload::text) > 0")
                        .param("a", person.botAddress())
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from audit_event where target_ref = :t and action ="
                                + " 'REAL_DEPOSIT_RECORDED'")
                        .param("t", "bot-wallet:" + person.botWalletId())
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from audit_event where target_ref = :t and action ="
                                + " 'REAL_BOT_WALLET_CLOSED'")
                        .param("t", "bot-wallet:" + person.botWalletId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void aPersonWithoutBotWalletClosesNothing() throws Exception {
        Person person = signIn(false, false);

        CloseOutcome outcome =
                transactions.execute(status -> anonymization.closeForAnonymization(person.tenantId(), person.userId()));

        assertThat(outcome).isEqualTo(new CloseOutcome.Closed(false));
    }

    @Autowired
    private com.memetrade.botwallet.infra.RealAudit realAudit;

    private com.memetrade.botwallet.infra.RealAudit audit() {
        return realAudit;
    }
}
