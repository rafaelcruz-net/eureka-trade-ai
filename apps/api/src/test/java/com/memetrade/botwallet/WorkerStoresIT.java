package com.memetrade.botwallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.domain.ChainPort;
import com.memetrade.botwallet.domain.DepositStore;
import com.memetrade.botwallet.domain.Withdrawal;
import com.memetrade.botwallet.domain.WithdrawalAmount;
import com.memetrade.botwallet.domain.WithdrawalKind;
import com.memetrade.botwallet.domain.WithdrawalState;
import com.memetrade.botwallet.domain.WithdrawalTransition;
import com.memetrade.botwallet.infra.JdbcBotWalletRepository;
import com.memetrade.botwallet.infra.JdbcWorkerStores;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.testsupport.TestWallet;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Step 18, infrastructure tested after the code (BR3.1, BR3.2, BR4.7, BR6.1, reliability-design.md): a deposit is
 * written once with its ledger entry, an unknown source only as HMAC with a reconciliation mark, the cursor moves, a
 * claimed withdrawal is hidden from the next claim, and a confirmation writes its entries and its audit event.
 */
@TestPropertySource(properties = "memetrade.real.enabled=true")
// U2 (BR1.6): REAL on with the simulated market sources is allowed only in the test and local profiles.
@org.springframework.test.context.ActiveProfiles("local")
class WorkerStoresIT extends AbstractRealIT {

    @Autowired
    private JdbcWorkerStores stores;

    @Autowired
    private JdbcBotWalletRepository repository;

    @Autowired
    private RealLedger ledger;

    private DepositStore.WalletToScan wallet(Person person) throws Exception {
        var challenge = body(call(
                person,
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/v1/real/consents/challenge")
                        .content("{\"kind\":\"ACTIVATE\",\"capLamports\":\"1000\"}")));
        call(
                person,
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/real/bot-wallet")
                        .content(json.writeValueAsString(java.util.Map.of(
                                "challengeId", challenge.get("challengeId").asText(),
                                "signature",
                                        person.wallet()
                                                .sign(challenge.get("message").asText())))));
        return stores.activeWallets().stream()
                .filter(w -> w.tenantId().equals(person.tenantId()))
                .findFirst()
                .orElseThrow();
    }

    private static ChainPort.IncomingTransfer transfer(String source, long lamports) {
        return new ChainPort.IncomingTransfer(txSignature(), source, lamports, java.time.Instant.EPOCH);
    }

    /** A real 64-byte Ed25519 signature in Base58, made in test code (the typed audit field checks the shape). */
    private static String txSignature() {
        return TestWallet.generate().sign(UUID.randomUUID().toString());
    }

    @Test
    void aDepositIsWrittenOnceWithItsLedgerEntryAndTheCursorMoves() throws Exception {
        Person person = signIn(true, true);
        DepositStore.WalletToScan target = wallet(person);
        ChainPort.IncomingTransfer deposit = transfer(person.wallet().address(), 1_000_000_000L);

        boolean first = stores.record(target, new DepositStore.RecordedDeposit(deposit, true, true));
        boolean again = stores.record(target, new DepositStore.RecordedDeposit(deposit, true, true));
        stores.advanceCursor(target, deposit.txSignature());

        assertThat(first).isTrue();
        assertThat(again).isFalse();
        assertThat(ledger.balance(person.tenantId(), target.botWalletId()).totalLamports())
                .isEqualTo(1_000_000_000L);
        assertThat(stores.activeWallets().stream()
                        .filter(w -> w.botWalletId().equals(target.botWalletId()))
                        .findFirst()
                        .orElseThrow()
                        .cursor())
                .isEqualTo(deposit.txSignature());
    }

    @Test
    void anUnknownSourceIsStoredOnlyAsHmacWithAReconciliationMark() throws Exception {
        Person person = signIn(true, true);
        DepositStore.WalletToScan target = wallet(person);
        String stranger = TestWallet.generate().address();

        stores.record(target, new DepositStore.RecordedDeposit(transfer(stranger, 7L), false, false));

        assertThat(jdbc.sql("select count(*) from reconciliation_mark where bot_wallet_id = :w")
                        .param("w", target.botWalletId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select source_address_hmac from observed_deposit where bot_wallet_id = :w")
                        .param("w", target.botWalletId())
                        .query(String.class)
                        .single())
                .hasSize(64)
                .isNotEqualTo(stranger);
    }

    @Test
    void aClaimedWithdrawalIsHiddenAndItsConfirmationWritesTheEntries() throws Exception {
        Person person = signIn(true, true);
        DepositStore.WalletToScan target = wallet(person);
        stores.record(
                target,
                new DepositStore.RecordedDeposit(transfer(person.wallet().address(), 2_000_000_000L), true, true));
        WithdrawalTransition requested = Withdrawal.request(
                person.tenantId(),
                target.botWalletId(),
                new WithdrawalAmount(WithdrawalKind.PARTIAL, 1_000L, 5_000L),
                UUID.randomUUID(),
                clock.instant());
        transactions.executeWithoutResult(status -> {
            repository.insertWithdrawal(requested.withdrawal());
            requested.entries().forEach(ledger::record);
        });

        // Other ITs of the shared database leave READY withdrawals behind (BotWalletRateLimitIT among them), so the
        // batches are claimed until this one shows up instead of assuming it is in the first batch of 20.
        Withdrawal found = null;
        for (List<Withdrawal> batch = stores.claimReady(clock.instant(), Duration.ofSeconds(60), 20);
                found == null && !batch.isEmpty();
                batch = stores.claimReady(clock.instant(), Duration.ofSeconds(60), 20)) {
            found = batch.stream()
                    .filter(w -> w.withdrawalId().equals(requested.withdrawal().withdrawalId()))
                    .findFirst()
                    .orElse(null);
        }
        assertThat(found).as("the withdrawal of this test is claimed").isNotNull();
        Withdrawal mine = found;
        List<Withdrawal> second = stores.claimReady(clock.instant(), Duration.ofSeconds(60), 20);
        assertThat(second).noneMatch(w -> w.withdrawalId().equals(mine.withdrawalId()));
        assertThat(stores.target(mine).orElseThrow().mainWalletAddress())
                .isEqualTo(person.wallet().address());

        String signature = txSignature();
        Withdrawal sent = mine.signed("c2lnbmVk", "aa".repeat(32), "hash", 10L, signature, clock.instant())
                .sent(clock.instant());
        stores.save(WithdrawalTransition.unchanged(sent));
        stores.save(sent.confirmed(signature, 5_000L, clock.instant()));

        var balance = ledger.balance(person.tenantId(), target.botWalletId());
        assertThat(balance.totalLamports()).isEqualTo(2_000_000_000L - 1_000L - 5_000L);
        assertThat(balance.reservedLamports()).isZero();
        assertThat(repository
                        .withdrawal(person.tenantId(), target.botWalletId(), mine.withdrawalId())
                        .orElseThrow()
                        .state())
                .isEqualTo(WithdrawalState.CONFIRMED);
    }
}
