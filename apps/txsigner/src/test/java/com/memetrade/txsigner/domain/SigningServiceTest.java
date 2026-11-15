package com.memetrade.txsigner.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.txsigner.codec.Base58;
import com.memetrade.txsigner.codec.SolanaTransaction;
import com.memetrade.txsigner.domain.kms.KeyTags;
import com.memetrade.txsigner.domain.kms.KmsUnavailableException;
import com.memetrade.txsigner.policy.Ed25519Verifier;
import com.memetrade.txsigner.policy.OperationMode;
import com.memetrade.txsigner.policy.PolicyRefusal;
import com.memetrade.txsigner.policy.WithdrawalContext;
import com.memetrade.txsigner.testsupport.InMemorySignerStore;
import com.memetrade.txsigner.testsupport.KmsSimulator;
import com.memetrade.txsigner.testsupport.MutableClock;
import com.memetrade.txsigner.testsupport.RecordingEvents;
import com.memetrade.txsigner.testsupport.TestWallet;
import com.memetrade.txsigner.testsupport.TxBytes;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Step 8, rules written before the code (BR4.2, BR5.1, BR5.2, NFR1.11, AC5.1.1): the KMS is called only after the
 * policy approves; every decision is recorded; a repetition with the same signingRequestId and message returns the
 * same signature without a second KMS call, and with another message is refused (REQUEST_MISMATCH), so a withdrawal
 * never has two signed transactions; with REAL off only WITHDRAWAL is signed; swaps wait for the U4.
 */
class SigningServiceTest {

    private static final long AMOUNT = 400_000_000L;
    private static final long FEE = 5_001L;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T12:00:00Z"));
    private final KmsSimulator kms = new KmsSimulator();
    private final InMemorySignerStore store = new InMemorySignerStore();
    private final RecordingEvents events = new RecordingEvents();
    private final TestWallet main = TestWallet.generate();
    private RecoveryPhase phase = RecoveryPhase.NORMAL;
    private String bot;
    private byte[] botKey;

    @BeforeEach
    void createWallet() {
        String keyRef = kms.createKey(new KeyTags("aa", UUID.randomUUID().toString(), "LOCALNET"));
        botKey = kms.publicKey(keyRef);
        bot = Base58.encode(botKey);
        store.put(new WalletPolicy(
                bot,
                keyRef,
                main.address(),
                2_000_000_000L,
                OperationMode.SIGNED,
                false,
                false,
                false,
                UUID.randomUUID(),
                100_000L,
                clock.instant(),
                clock.instant()));
    }

    private SigningService service(boolean realEnabled) {
        SignerSettings settings =
                new SignerSettings(realEnabled, "LOCALNET", 10_000_000_000L, 100_000L, 1_000, 5_000L, 30);
        return new SigningService(settings, clock, kms, store, events, () -> phase);
    }

    private String withdrawalTo(byte[] destination, long lamports) {
        byte[] message =
                TxBytes.withdrawal(botKey, destination, lamports, 450, 2L).build();
        return Base64.getEncoder().encodeToString(TxBytes.unsigned(message, 1));
    }

    private SignCommand withdrawal(UUID id, String transaction) {
        return new SignCommand(id, bot, OperationKind.WITHDRAWAL, transaction, new WithdrawalContext(AMOUNT, FEE));
    }

    @Test
    void anApprovedWithdrawalIsSignedByTheKmsAndRecorded() {
        UUID id = UUID.randomUUID();

        SigningOutcome outcome = service(true).sign(withdrawal(id, withdrawalTo(main.publicKey(), AMOUNT)));

        assertThat(outcome.signed()).isTrue();
        SolanaTransaction signed = SolanaTransaction.decodeBase64(outcome.signedTransactionBase64());
        assertThat(Base58.encode(signed.signatures().get(0))).isEqualTo(outcome.signature());
        assertThat(new Ed25519Verifier()
                        .verify(
                                botKey,
                                signed.messageBytes(),
                                signed.signatures().get(0)))
                .isTrue();
        assertThat(store.findSigningRecord(id).orElseThrow().decision()).isEqualTo(SigningRecord.Decision.SIGNED);
        assertThat(events.types()).containsExactly(SignerEvent.Type.SIGNED);
    }

    @Test
    void repeatingTheSameRequestReturnsTheSameSignatureWithoutASecondKmsCall() {
        UUID id = UUID.randomUUID();
        SignCommand command = withdrawal(id, withdrawalTo(main.publicKey(), AMOUNT));

        SigningOutcome first = service(true).sign(command);
        SigningOutcome again = service(true).sign(command);

        assertThat(again).isEqualTo(first);
        assertThat(kms.signCalls()).isEqualTo(1);
        assertThat(store.recordCount()).isEqualTo(1);
    }

    @Test
    void theSameRequestWithAnotherMessageIsRefusedSoAWithdrawalNeverHasTwoTransactions() {
        UUID id = UUID.randomUUID();
        service(true).sign(withdrawal(id, withdrawalTo(main.publicKey(), AMOUNT)));
        byte[] otherBlockhash = TxBytes.withdrawal(botKey, main.publicKey(), AMOUNT, 450, 2L)
                .blockhash(TxBytes.filled(8))
                .build();

        SigningOutcome outcome = service(true)
                .sign(withdrawal(id, Base64.getEncoder().encodeToString(TxBytes.unsigned(otherBlockhash, 1))));

        assertThat(outcome.signed()).isFalse();
        assertThat(outcome.refusal()).isEqualTo(PolicyRefusal.REQUEST_MISMATCH);
        assertThat(kms.signCalls()).isEqualTo(1);
        assertThat(events.types()).endsWith(SignerEvent.Type.REQUEST_MISMATCH);
    }

    @Test
    void aWithdrawalToAnotherAddressIsRefusedRecordedAndNeverReachesTheKms() {
        UUID id = UUID.randomUUID();

        SigningOutcome outcome = service(true)
                .sign(withdrawal(id, withdrawalTo(TestWallet.generate().publicKey(), AMOUNT)));

        assertThat(outcome.refusal()).isEqualTo(PolicyRefusal.DESTINATION_NOT_ALLOWED);
        assertThat(kms.signCalls()).isZero();
        SigningRecord record = store.findSigningRecord(id).orElseThrow();
        assertThat(record.decision()).isEqualTo(SigningRecord.Decision.REFUSED);
        assertThat(record.refusalCode()).isEqualTo("DESTINATION_NOT_ALLOWED");
        assertThat(record.signedTransactionBase64()).isNull();
        assertThat(events.all()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(SignerEvent.Type.SIGNING_REFUSED);
            assertThat(event.reason()).isEqualTo("DESTINATION_NOT_ALLOWED");
        });
    }

    @Test
    void withRealOffAWithdrawalIsStillSigned() {
        SigningOutcome outcome =
                service(false).sign(withdrawal(UUID.randomUUID(), withdrawalTo(main.publicKey(), AMOUNT)));

        assertThat(outcome.signed()).isTrue();
    }

    @Test
    void swapsAreRefusedWithRealOffAndUntilTheU4WithRealOn() {
        String transaction = withdrawalTo(main.publicKey(), AMOUNT);
        SignCommand swap = new SignCommand(UUID.randomUUID(), bot, OperationKind.SWAP_BUY, transaction, null);
        SignCommand sell = new SignCommand(UUID.randomUUID(), bot, OperationKind.SWAP_SELL, transaction, null);

        assertThat(service(false).sign(swap).refusal()).isEqualTo(PolicyRefusal.REAL_DISABLED);
        assertThat(service(true).sign(sell).refusal()).isEqualTo(PolicyRefusal.OPERATION_NOT_SUPPORTED);
        assertThat(kms.signCalls()).isZero();
    }

    @Test
    void anUnknownOrBlockedWalletIsRefused() {
        SignCommand unknown = new SignCommand(
                UUID.randomUUID(),
                Base58.encode(TxBytes.filled(9)),
                OperationKind.WITHDRAWAL,
                withdrawalTo(main.publicKey(), AMOUNT),
                new WithdrawalContext(AMOUNT, FEE));
        assertThat(service(true).sign(unknown).refusal()).isEqualTo(PolicyRefusal.WALLET_NOT_FOUND);

        store.put(store.findPolicy(bot).orElseThrow().blockedAt(clock.instant()));
        assertThat(service(true)
                        .sign(withdrawal(UUID.randomUUID(), withdrawalTo(main.publicKey(), AMOUNT)))
                        .refusal())
                .isEqualTo(PolicyRefusal.WALLET_BLOCKED);
        assertThat(kms.signCalls()).isZero();
    }

    @Test
    void aKmsOutageRecordsNothingSoTheSameRequestCanBeRetried() {
        UUID id = UUID.randomUUID();
        SignCommand command = withdrawal(id, withdrawalTo(main.publicKey(), AMOUNT));
        kms.goDown();

        assertThatThrownBy(() -> service(true).sign(command)).isInstanceOf(KmsUnavailableException.class);
        assertThat(store.findSigningRecord(id)).isEmpty();

        kms.recover();
        assertThat(service(true).sign(command).signed()).isTrue();
    }

    @Test
    void nothingIsSignedWhileTheRecoveryWaitsAndOnlyWithdrawalsAfter() {
        phase = RecoveryPhase.WAITING;
        SigningOutcome waiting =
                service(true).sign(withdrawal(UUID.randomUUID(), withdrawalTo(main.publicKey(), AMOUNT)));
        assertThat(waiting.refusal()).isEqualTo(PolicyRefusal.RECOVERY_IN_PROGRESS);

        phase = RecoveryPhase.WITHDRAWALS_ONLY;
        assertThat(service(true)
                        .sign(withdrawal(UUID.randomUUID(), withdrawalTo(main.publicKey(), AMOUNT)))
                        .signed())
                .isTrue();
    }

    @Test
    void aMalformedTransactionIsRefusedNeverSigned() {
        UUID id = UUID.randomUUID();

        SigningOutcome outcome = service(true).sign(withdrawal(id, "AAAA"));

        assertThat(outcome.refusal()).isEqualTo(PolicyRefusal.MALFORMED_TRANSACTION);
        assertThat(store.findSigningRecord(id).orElseThrow().messageDigest()).hasSize(64);
        assertThat(kms.signCalls()).isZero();
    }
}
