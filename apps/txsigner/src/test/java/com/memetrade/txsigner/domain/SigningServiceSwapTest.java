package com.memetrade.txsigner.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.txsigner.codec.Base58;
import com.memetrade.txsigner.codec.SolanaTransaction;
import com.memetrade.txsigner.domain.kms.KeyTags;
import com.memetrade.txsigner.policy.Ed25519Verifier;
import com.memetrade.txsigner.policy.OperationMode;
import com.memetrade.txsigner.policy.PolicyRefusal;
import com.memetrade.txsigner.policy.SwapContext;
import com.memetrade.txsigner.policy.SwapMode;
import com.memetrade.txsigner.testsupport.InMemorySignerStore;
import com.memetrade.txsigner.testsupport.KmsSimulator;
import com.memetrade.txsigner.testsupport.MutableClock;
import com.memetrade.txsigner.testsupport.RecordingEvents;
import com.memetrade.txsigner.testsupport.SwapTx;
import com.memetrade.txsigner.testsupport.TestWallet;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Step 4 of the U4, written BEFORE the code (BR4.1, BR4.2, BR4.3, U4-NFR1.2, contract R1 addendum of the U4): the
 * TxSigner signs a SWAP only inside its policy, with the cap and the mode of its OWN WalletPolicy; in AUTO_FULL the bot
 * wallet signs alone, in SIGNED it adds its signature after the one of the main wallet; the same signingRequestId
 * returns the same signature without a second KMS call and with another content is refused (REQUEST_MISMATCH); with the
 * REAL off no swap is signed; every refusal keeps its reason.
 */
class SigningServiceSwapTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-15T12:00:00Z"));
    private final KmsSimulator kms = new KmsSimulator();
    private final InMemorySignerStore store = new InMemorySignerStore();
    private final RecordingEvents events = new RecordingEvents();
    private final TestWallet main = TestWallet.generate();
    private String bot;
    private byte[] botKey;
    private String keyRef;

    @BeforeEach
    void createWallet() {
        keyRef = kms.createKey(new KeyTags("bb", UUID.randomUUID().toString(), "LOCALNET"));
        botKey = kms.publicKey(keyRef);
        bot = Base58.encode(botKey);
        policy(OperationMode.AUTO_FULL, false);
    }

    private void policy(OperationMode mode, boolean blocked) {
        store.put(new WalletPolicy(
                bot,
                keyRef,
                main.address(),
                2_000_000_000L,
                mode,
                false,
                blocked,
                false,
                UUID.randomUUID(),
                100_000L,
                clock.instant(),
                clock.instant()));
    }

    private SigningService service(boolean realEnabled) {
        SignerSettings settings =
                new SignerSettings(realEnabled, "LOCALNET", 10_000_000_000L, 100_000L, 1_000, 5_000L, 30, 1_000_000L);
        return new SigningService(settings, clock, kms, store, events, () -> RecoveryPhase.NORMAL);
    }

    private SignCommand swap(UUID id, SwapTx tx) {
        return SignCommand.swap(id, bot, tx.base64(), tx.context());
    }

    private SwapTx buy() {
        return new SwapTx(botKey, main);
    }

    @Test
    void anAutoFullSwapIsSignedByTheBotWalletInTheFirstSlot() {
        SwapTx tx = buy();

        SigningOutcome outcome = service(true).sign(swap(UUID.randomUUID(), tx));

        assertThat(outcome.signed()).as(String.valueOf(outcome.refusal())).isTrue();
        SolanaTransaction signed = SolanaTransaction.decodeBase64(outcome.signedTransactionBase64());
        assertThat(signed.messageBytes()).isEqualTo(tx.message());
        assertThat(new Ed25519Verifier()
                        .verify(
                                botKey,
                                signed.messageBytes(),
                                signed.signatures().get(0)))
                .isTrue();
        assertThat(outcome.signature())
                .isEqualTo(Base58.encode(signed.signatures().get(0)));
    }

    @Test
    void aSignedSwapKeepsTheMainSignatureAndAddsTheBotSignatureInTheSecondSlot() {
        policy(OperationMode.SIGNED, false);
        SwapTx tx = buy();
        tx.mode = SwapMode.SIGNED;

        SigningOutcome outcome = service(true).sign(swap(UUID.randomUUID(), tx));

        assertThat(outcome.signed()).as(String.valueOf(outcome.refusal())).isTrue();
        SolanaTransaction signed = SolanaTransaction.decodeBase64(outcome.signedTransactionBase64());
        Ed25519Verifier verifier = new Ed25519Verifier();
        assertThat(verifier.verify(
                        main.publicKey(),
                        signed.messageBytes(),
                        signed.signatures().get(0)))
                .isTrue();
        assertThat(verifier.verify(
                        botKey, signed.messageBytes(), signed.signatures().get(1)))
                .isTrue();
        assertThat(outcome.signature())
                .as("the transaction id is the first signature, the one of the fee payer")
                .isEqualTo(Base58.encode(signed.signatures().get(0)));
    }

    @Test
    void theSameRequestReturnsTheSameSignatureWithoutASecondKmsCall() {
        SwapTx tx = buy();
        UUID id = UUID.randomUUID();
        SigningService service = service(true);

        SigningOutcome first = service.sign(swap(id, tx));
        int calls = kms.signCalls();
        SigningOutcome again = service.sign(swap(id, tx));

        assertThat(again.signature()).isEqualTo(first.signature());
        assertThat(again.signedTransactionBase64()).isEqualTo(first.signedTransactionBase64());
        assertThat(kms.signCalls()).isEqualTo(calls);
    }

    @Test
    void theSameRequestWithAnotherContentIsRefusedAsAMismatch() {
        UUID id = UUID.randomUUID();
        SigningService service = service(true);
        service.sign(swap(id, buy()));
        SwapTx other = buy();
        other.in = 60_000_000L;

        SigningOutcome outcome = service.sign(swap(id, other));

        assertThat(outcome.refusal()).isEqualTo(PolicyRefusal.REQUEST_MISMATCH);
    }

    @Test
    void withTheRealOffNoSwapIsSignedAndTheKmsIsNeverCalled() {
        int calls = kms.signCalls();

        SigningOutcome outcome = service(false).sign(swap(UUID.randomUUID(), buy()));

        assertThat(outcome.refusal()).isEqualTo(PolicyRefusal.REAL_DISABLED);
        assertThat(kms.signCalls()).isEqualTo(calls);
    }

    @Test
    void aRefusalOfThePolicyKeepsItsReasonAndNeverCallsTheKms() {
        SwapTx tx = buy();
        tx.closeDestination = TestWallet.generate().publicKey();
        int calls = kms.signCalls();

        SigningOutcome outcome = service(true).sign(swap(UUID.randomUUID(), tx));

        assertThat(outcome.refusal()).isEqualTo(PolicyRefusal.CLOSE_NOT_ALLOWED);
        assertThat(kms.signCalls()).isEqualTo(calls);
        assertThat(events.all()).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo(SignerEvent.Type.SIGNING_REFUSED);
            assertThat(event.operation()).isEqualTo("SWAP");
            assertThat(event.reason()).isEqualTo("CLOSE_NOT_ALLOWED");
        });
    }

    @Test
    void aBlockedOrUnknownWalletIsRefused() {
        policy(OperationMode.AUTO_FULL, true);

        assertThat(service(true).sign(swap(UUID.randomUUID(), buy())).refusal())
                .isEqualTo(PolicyRefusal.WALLET_BLOCKED);
        SwapTx unknown = new SwapTx(TestWallet.generate().publicKey(), main);
        assertThat(service(true)
                        .sign(SignCommand.swap(
                                UUID.randomUUID(), Base58.encode(unknown.bot), unknown.base64(), unknown.context()))
                        .refusal())
                .isEqualTo(PolicyRefusal.WALLET_NOT_FOUND);
    }

    @Test
    void theOldSwapKindsStayUnsupported() {
        SwapTx tx = buy();
        SignCommand old = new SignCommand(UUID.randomUUID(), bot, OperationKind.SWAP_BUY, tx.base64(), null);

        assertThat(service(true).sign(old).refusal()).isEqualTo(PolicyRefusal.OPERATION_NOT_SUPPORTED);
    }

    @Test
    void aSwapWithoutItsContextIsRefused() {
        SwapTx tx = buy();
        SignCommand bare = SignCommand.swap(UUID.randomUUID(), bot, tx.base64(), (SwapContext) null);

        assertThat(service(true).sign(bare).refusal()).isEqualTo(PolicyRefusal.CONTEXT_MISSING);
    }
}
