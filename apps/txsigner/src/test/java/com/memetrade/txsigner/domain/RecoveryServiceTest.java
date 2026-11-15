package com.memetrade.txsigner.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.txsigner.codec.Base58;
import com.memetrade.txsigner.domain.kms.KeyTags;
import com.memetrade.txsigner.policy.OperationMode;
import com.memetrade.txsigner.policy.PolicyRefusal;
import com.memetrade.txsigner.policy.WithdrawalContext;
import com.memetrade.txsigner.testsupport.InMemorySignerStore;
import com.memetrade.txsigner.testsupport.KmsSimulator;
import com.memetrade.txsigner.testsupport.MutableClock;
import com.memetrade.txsigner.testsupport.RecordingEvents;
import com.memetrade.txsigner.testsupport.TestWallet;
import com.memetrade.txsigner.testsupport.TxBytes;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Step 10, written before the code (NFR4.13, security-design.md "Modo de recuperação", infrastructure-specification.md
 * "Âncora sem o endereço"): after a restore the TxSigner signs nothing for five minutes, then only withdrawals; a
 * policy comes back only when the main wallet sent by the api matches the MAC tag of the key, with cap 0 and SIGNED;
 * a wrong network or MAC blocks the key; the end writes recoveryCompletedAt.
 */
class RecoveryServiceTest {

    private static final Duration WAIT = Duration.ofMinutes(5);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T12:00:00Z"));
    private final KmsSimulator kms = new KmsSimulator();
    private final InMemorySignerStore store = new InMemorySignerStore();
    private final RecordingEvents events = new RecordingEvents();
    private final TestWallet main = TestWallet.generate();
    private final SignerSettings settings =
            new SignerSettings(true, "LOCALNET", 10_000_000_000L, 100_000L, 1_000, 5_000L, 30);

    private RecoveryService recovering() {
        RecoveryService service = new RecoveryService(settings, clock, kms, store, events, true, WAIT);
        service.start();
        return service;
    }

    private String keyFor(TestWallet mainWallet, String network) {
        String mac = HexFormat.of().formatHex(kms.mac(mainWallet.address().getBytes(StandardCharsets.UTF_8)));
        String keyRef = kms.createKey(new KeyTags(mac, UUID.randomUUID().toString(), network));
        return Base58.encode(kms.publicKey(keyRef));
    }

    @Test
    void withoutTheRecoveryOptionThePhaseIsNormal() {
        RecoveryService normal = new RecoveryService(settings, clock, kms, store, events, false, WAIT);
        normal.start();

        assertThat(normal.phase()).isEqualTo(RecoveryPhase.NORMAL);
    }

    @Test
    void nothingIsSignedForFiveMinutesAndThenOnlyWithdrawals() {
        RecoveryService service = recovering();

        assertThat(service.phase()).isEqualTo(RecoveryPhase.WAITING);
        clock.advance(WAIT.minusSeconds(1));
        assertThat(service.phase()).isEqualTo(RecoveryPhase.WAITING);
        clock.advance(Duration.ofSeconds(1));
        assertThat(service.phase()).isEqualTo(RecoveryPhase.WITHDRAWALS_ONLY);
    }

    @Test
    void aWalletComesBackFromItsKeyTagsWithCapZeroAndSignedWhenTheMacMatches() {
        String bot = keyFor(main, "LOCALNET");

        PolicyView restored = recovering().restore(new RestoreWalletCommand(bot, main.address(), null, null));

        assertThat(restored.capLamports()).isZero();
        assertThat(restored.mode()).isEqualTo(OperationMode.SIGNED);
        WalletPolicy policy = store.findPolicy(bot).orElseThrow();
        assertThat(policy.restored()).isTrue();
        assertThat(policy.mainWalletAddress()).isEqualTo(main.address());
        assertThat(events.types()).contains(SignerEvent.Type.WALLET_RESTORED);
    }

    @Test
    void aMainWalletThatDoesNotMatchTheMacIsRefusedAndNoPolicyIsCreated() {
        String bot = keyFor(main, "LOCALNET");
        String attacker = TestWallet.generate().address();

        assertThatThrownBy(() -> recovering().restore(new RestoreWalletCommand(bot, attacker, null, null)))
                .isInstanceOfSatisfying(SignerException.class, e -> {
                    assertThat(e.error()).isEqualTo(SignerError.RECOVERY_REFUSED);
                    assertThat(e.reason()).isEqualTo("MAC_MISMATCH");
                });
        assertThat(store.findPolicy(bot)).isEmpty();
        assertThat(events.types()).contains(SignerEvent.Type.WALLET_BLOCKED);
    }

    @Test
    void aKeyOfAnotherNetworkIsRefused() {
        String bot = keyFor(main, "MAINNET");

        assertThatThrownBy(() -> recovering().restore(new RestoreWalletCommand(bot, main.address(), null, null)))
                .isInstanceOfSatisfying(
                        SignerException.class, e -> assertThat(e.reason()).isEqualTo("NETWORK_MISMATCH"));
        assertThat(store.findPolicy(bot)).isEmpty();
    }

    @Test
    void anUnknownWalletIsRefused() {
        assertThatThrownBy(() -> recovering()
                        .restore(
                                new RestoreWalletCommand(Base58.encode(TxBytes.filled(4)), main.address(), null, null)))
                .isInstanceOfSatisfying(
                        SignerException.class, e -> assertThat(e.reason()).isEqualTo("KEY_NOT_FOUND"));
    }

    @Test
    void restoringTwiceKeepsOnePolicyAndAppliesTheMoreRestrictiveLimitsOfTheApi() {
        String bot = keyFor(main, "LOCALNET");
        RecoveryService service = recovering();
        service.restore(new RestoreWalletCommand(bot, main.address(), null, null));
        WalletPolicy current = store.findPolicy(bot).orElseThrow();
        store.updatePolicy(current.withLimits(3_000_000_000L, OperationMode.AUTO_FULL, clock.instant()));

        PolicyView again = service.restore(
                new RestoreWalletCommand(bot, main.address(), 1_000_000_000L, OperationMode.AUTO_EXITS));

        assertThat(again.capLamports()).isEqualTo(1_000_000_000L);
        assertThat(again.mode()).isEqualTo(OperationMode.AUTO_EXITS);
    }

    @Test
    void aRestoredPolicyWhoseMainWalletDiffersFromTheAnchorIsBlocked() {
        String bot = keyFor(main, "LOCALNET");
        RecoveryService service = recovering();
        service.restore(new RestoreWalletCommand(bot, main.address(), null, null));
        WalletPolicy policy = store.findPolicy(bot).orElseThrow();
        String attacker = TestWallet.generate().address();
        store.put(new WalletPolicy(
                bot,
                policy.kmsKeyRef(),
                attacker,
                0,
                OperationMode.SIGNED,
                false,
                false,
                true,
                policy.creationRequestId(),
                100_000L,
                policy.createdAt(),
                policy.updatedAt()));

        assertThatThrownBy(() -> service.restore(new RestoreWalletCommand(bot, main.address(), null, null)))
                .isInstanceOfSatisfying(
                        SignerException.class, e -> assertThat(e.reason()).isEqualTo("ANCHOR_MISMATCH"));
        assertThat(store.findPolicy(bot).orElseThrow().blocked()).isTrue();
    }

    @Test
    void completingBeforeTheWaitIsRefusedAndAfterItWritesRecoveryCompletedAt() {
        RecoveryService service = recovering();

        assertThatThrownBy(service::complete).isInstanceOfSatisfying(SignerException.class, e -> assertThat(e.error())
                .isEqualTo(SignerError.RECOVERY_IN_PROGRESS));
        clock.advance(WAIT);
        service.complete();

        assertThat(service.phase()).isEqualTo(RecoveryPhase.NORMAL);
        assertThat(store.recoveryCompletedAt(service.runId())).isEqualTo(clock.instant());
        assertThat(events.types()).contains(SignerEvent.Type.RECOVERY_COMPLETED);
    }

    @Test
    void aRestoredWalletSignsAWithdrawalToTheAnchoredMainWalletAfterTheWait() {
        String bot = keyFor(main, "LOCALNET");
        RecoveryService service = recovering();
        service.restore(new RestoreWalletCommand(bot, main.address(), null, null));
        SigningService signing = new SigningService(settings, clock, kms, store, events, service);
        byte[] message = TxBytes.withdrawal(Base58.decode(bot), main.publicKey(), 10L, null, null)
                .build();
        SignCommand command = new SignCommand(
                UUID.randomUUID(),
                bot,
                OperationKind.WITHDRAWAL,
                Base64.getEncoder().encodeToString(TxBytes.unsigned(message, 1)),
                new WithdrawalContext(10L, 5_000L));

        assertThat(signing.sign(command).refusal()).isEqualTo(PolicyRefusal.RECOVERY_IN_PROGRESS);
        clock.advance(WAIT);
        assertThat(signing.sign(command).signed()).isTrue();
    }
}
