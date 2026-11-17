package com.memetrade.txsigner.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.txsigner.codec.Base58;
import com.memetrade.txsigner.domain.kms.KeyTags;
import com.memetrade.txsigner.policy.OperationMode;
import com.memetrade.txsigner.policy.PolicyRefusal;
import com.memetrade.txsigner.testsupport.InMemorySignerStore;
import com.memetrade.txsigner.testsupport.KmsSimulator;
import com.memetrade.txsigner.testsupport.MutableClock;
import com.memetrade.txsigner.testsupport.RecordingEvents;
import com.memetrade.txsigner.testsupport.TestWallet;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Step 11 of the U7 of 261113-trading-real-bot, written BEFORE the code (BR0.3, S44, U7-NFR1.2): until the real
 * recording of the Jupiter Trigger API, the TxSigner refuses {@code TRIGGER_CREATE} and {@code TRIGGER_CANCEL} with
 * {@code POLICY_NOT_RECORDED}, whatever the REAL switch, without calling the KMS; nothing is recorded, so the same
 * request keeps the same answer once the policy exists.
 */
class SigningServiceTriggerTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-17T12:00:00Z"));
    private final KmsSimulator kms = new KmsSimulator();
    private final InMemorySignerStore store = new InMemorySignerStore();
    private final RecordingEvents events = new RecordingEvents();
    private final TestWallet main = TestWallet.generate();
    private String bot;

    @BeforeEach
    void createWallet() {
        String keyRef = kms.createKey(new KeyTags("bb", UUID.randomUUID().toString(), "LOCALNET"));
        bot = Base58.encode(kms.publicKey(keyRef));
        store.put(new WalletPolicy(
                bot,
                keyRef,
                main.address(),
                2_000_000_000L,
                OperationMode.AUTO_EXITS,
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
                new SignerSettings(realEnabled, "LOCALNET", 10_000_000_000L, 100_000L, 1_000, 5_000L, 30, 1_000_000L);
        return new SigningService(settings, clock, kms, store, events, () -> RecoveryPhase.NORMAL);
    }

    private SignCommand trigger(OperationKind kind, UUID id) {
        return new SignCommand(id, bot, kind, "AQID", null, null);
    }

    @Test
    void s44ACreationOfAConditionalIsRefusedUntilTheRecordingAndTheKmsIsNeverCalled() {
        SigningOutcome outcome = service(true).sign(trigger(OperationKind.TRIGGER_CREATE, UUID.randomUUID()));

        assertThat(outcome.signed()).isFalse();
        assertThat(outcome.refusal()).isEqualTo(PolicyRefusal.POLICY_NOT_RECORDED);
        assertThat(kms.signCalls()).isZero();
    }

    @Test
    void theCancellationIsRefusedTheSameWayUntilTheRecording() {
        SigningOutcome outcome = service(true).sign(trigger(OperationKind.TRIGGER_CANCEL, UUID.randomUUID()));

        assertThat(outcome.refusal()).isEqualTo(PolicyRefusal.POLICY_NOT_RECORDED);
    }

    /** BR1.3: the cancellation never depends on the REAL switch; until the recording, the reason is the same. */
    @Test
    void withTheRealOffTheReasonIsStillThePolicyNotRecorded() {
        SigningOutcome outcome = service(false).sign(trigger(OperationKind.TRIGGER_CANCEL, UUID.randomUUID()));

        assertThat(outcome.refusal()).isEqualTo(PolicyRefusal.POLICY_NOT_RECORDED);
    }

    @Test
    void theRefusalIsNotRecordedSoTheSameRequestGetsTheSameAnswerAgain() {
        UUID id = UUID.randomUUID();
        SigningService service = service(true);

        service.sign(trigger(OperationKind.TRIGGER_CREATE, id));
        SigningOutcome again = service.sign(trigger(OperationKind.TRIGGER_CREATE, id));

        assertThat(again.refusal()).isEqualTo(PolicyRefusal.POLICY_NOT_RECORDED);
        assertThat(store.findSigningRecord(id)).isEmpty();
    }
}
