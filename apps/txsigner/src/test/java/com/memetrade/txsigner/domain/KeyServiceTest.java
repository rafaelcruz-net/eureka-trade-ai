package com.memetrade.txsigner.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.txsigner.codec.Base58;
import com.memetrade.txsigner.domain.kms.KeyTags;
import com.memetrade.txsigner.domain.kms.KmsUnavailableException;
import com.memetrade.txsigner.policy.OperationMode;
import com.memetrade.txsigner.testsupport.ConsentText;
import com.memetrade.txsigner.testsupport.InMemorySignerStore;
import com.memetrade.txsigner.testsupport.KmsSimulator;
import com.memetrade.txsigner.testsupport.MutableClock;
import com.memetrade.txsigner.testsupport.RecordingEvents;
import com.memetrade.txsigner.testsupport.TestWallet;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Step 8, rules written before the code (BR1.1, BR1.6, BR1.8, BR1.10, BR2.2, BR5.3, NFR1.2 g): the TxSigner creates
 * the key only with REAL on and a consent it checked itself, tags it with the HMAC of the main wallet (never the
 * address), is idempotent by requestId, records the nonce once, and only widens a policy with a new consent.
 */
class KeyServiceTest {

    private static final long TEN_SOL = 10_000_000_000L;
    private static final Random SEEDED = new Random(7);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T12:01:00Z"));
    private final KmsSimulator kms = new KmsSimulator();
    private final InMemorySignerStore store = new InMemorySignerStore();
    private final RecordingEvents events = new RecordingEvents();
    private final TestWallet main = TestWallet.generate();
    private RecoveryPhase phase = RecoveryPhase.NORMAL;

    private KeyService service(boolean realEnabled, int maxKeysPerHour) {
        SignerSettings settings =
                new SignerSettings(realEnabled, "LOCALNET", TEN_SOL, 100_000L, 1_000, 5_000L, maxKeysPerHour);
        return new KeyService(settings, clock, kms, store, events, () -> phase);
    }

    private KeyService on() {
        return service(true, 30);
    }

    private static String nonce() {
        byte[] bytes = new byte[32];
        SEEDED.nextBytes(bytes);
        return Base58.encode(bytes);
    }

    private CreateWalletCommand activation(UUID requestId, String message) {
        return new CreateWalletCommand(requestId, main.address(), message, main.sign(message));
    }

    private CreateWalletCommand activation(UUID requestId) {
        return activation(
                requestId, ConsentText.activation(main.address()).nonce(nonce()).text());
    }

    @Test
    void withRealOffNoKeyIsCreatedAndNothingIsAskedToTheKms() {
        assertThatThrownBy(() -> service(false, 30).createWallet(activation(UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        SignerException.class, e -> assertThat(e.error()).isEqualTo(SignerError.REAL_DISABLED));
        assertThat(kms.createCalls()).isZero();
        assertThat(kms.signCalls()).isZero();
    }

    @Test
    void aValidActivationCreatesTheKeyWithTheFourTagsAndTheMacOfTheMainWallet() {
        UUID requestId = UUID.randomUUID();

        CreatedWallet created = on().createWallet(activation(requestId));

        WalletPolicy policy = store.findPolicy(created.botWalletAddress()).orElseThrow();
        String expectedMac = HexFormat.of().formatHex(kms.mac(main.address().getBytes(StandardCharsets.UTF_8)));
        assertThat(kms.tags(policy.kmsKeyRef()))
                .containsEntry(KeyTags.APP, KeyTags.APP_VALUE)
                .containsEntry(KeyTags.MAIN_WALLET_MAC, expectedMac)
                .containsEntry(KeyTags.CREATION_REQUEST_ID, requestId.toString())
                .containsEntry(KeyTags.NETWORK, "LOCALNET")
                .doesNotContainValue(main.address());
        assertThat(Base58.decode(created.botWalletAddress())).isEqualTo(kms.publicKey(policy.kmsKeyRef()));
        assertThat(policy.mainWalletAddress()).isEqualTo(main.address());
        assertThat(policy.authorizedMode()).isEqualTo(OperationMode.SIGNED);
        assertThat(policy.authorizedCapLamports()).isEqualTo(2_000_000_000L);
        assertThat(policy.maxWithdrawalFeeLamports()).isEqualTo(100_000L);
        assertThat(store.nonceCount()).isEqualTo(1);
        assertThat(events.types()).containsExactly(SignerEvent.Type.WALLET_CREATED);
    }

    @Test
    void theSameRequestIdReturnsTheSameWalletWithoutASecondKey() {
        UUID requestId = UUID.randomUUID();
        CreateWalletCommand command = activation(requestId);

        CreatedWallet first = on().createWallet(command);
        CreatedWallet again = on().createWallet(command);

        assertThat(again).isEqualTo(first);
        assertThat(kms.createCalls()).isEqualTo(1);
    }

    @Test
    void theSameRequestIdForAnotherMainWalletIsAMismatch() {
        UUID requestId = UUID.randomUUID();
        on().createWallet(activation(requestId));
        TestWallet other = TestWallet.generate();
        String message = ConsentText.activation(other.address()).nonce(nonce()).text();

        assertThatThrownBy(() -> on().createWallet(
                                new CreateWalletCommand(requestId, other.address(), message, other.sign(message))))
                .isInstanceOfSatisfying(
                        SignerException.class, e -> assertThat(e.error()).isEqualTo(SignerError.REQUEST_MISMATCH));
    }

    @Test
    void anInvalidConsentCreatesNoKey() {
        String message = ConsentText.activation(main.address()).nonce(nonce()).text();
        CreateWalletCommand forged = new CreateWalletCommand(
                UUID.randomUUID(),
                main.address(),
                message,
                TestWallet.generate().sign(message));

        assertThatThrownBy(() -> on().createWallet(forged))
                .isInstanceOfSatisfying(
                        SignerException.class, e -> assertThat(e.error()).isEqualTo(SignerError.CONSENT_INVALID));
        assertThat(kms.createCalls()).isZero();
        assertThat(events.types()).containsExactly(SignerEvent.Type.WALLET_CREATION_REFUSED);
    }

    @Test
    void aNonceUsedByAnotherRequestIsRefused() {
        String message = ConsentText.activation(main.address()).nonce(nonce()).text();
        on().createWallet(activation(UUID.randomUUID(), message));

        assertThatThrownBy(() -> on().createWallet(activation(UUID.randomUUID(), message)))
                .isInstanceOfSatisfying(
                        SignerException.class, e -> assertThat(e.error()).isEqualTo(SignerError.CONSENT_INVALID));
        assertThat(kms.createCalls()).isEqualTo(1);
    }

    @Test
    void moreKeysThanTheHourlyLimitAreRefused() {
        KeyService limited = service(true, 2);
        limited.createWallet(activation(UUID.randomUUID()));
        limited.createWallet(activation(UUID.randomUUID()));

        assertThatThrownBy(() -> limited.createWallet(activation(UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        SignerException.class, e -> assertThat(e.error()).isEqualTo(SignerError.KEY_RATE_LIMITED));
        clock.advance(Duration.ofMinutes(61));
        String later = ConsentText.activation(main.address())
                .nonce(nonce())
                .issuedAt(clock.instant().minusSeconds(30))
                .expiresAt(clock.instant().minusSeconds(30).plus(Duration.ofMinutes(5)))
                .text();
        assertThat(limited.createWallet(activation(UUID.randomUUID(), later))).isNotNull();
    }

    @Test
    void aKmsOutageLeavesNoPolicyAndTheSameRequestWorksLater() {
        UUID requestId = UUID.randomUUID();
        CreateWalletCommand command = activation(requestId);
        kms.goDown();

        assertThatThrownBy(() -> on().createWallet(command)).isInstanceOf(KmsUnavailableException.class);
        assertThat(store.findPolicyByCreationRequest(requestId)).isEmpty();
        assertThat(store.nonceCount()).isZero();

        kms.recover();
        assertThat(on().createWallet(command).botWalletAddress()).isNotBlank();
    }

    @Test
    void creationIsRefusedWhileRecovering() {
        phase = RecoveryPhase.WITHDRAWALS_ONLY;

        assertThatThrownBy(() -> on().createWallet(activation(UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        SignerException.class, e -> assertThat(e.error()).isEqualTo(SignerError.RECOVERY_IN_PROGRESS));
        assertThat(kms.createCalls()).isZero();
    }

    @Test
    void loweringAppliesTheMoreRestrictiveValueWithoutSignatureEvenWithRealOff() {
        String wallet = on().createWallet(activation(UUID.randomUUID())).botWalletAddress();

        PolicyView lowered =
                service(false, 30).changePolicy(ChangePolicyCommand.lowerCap(UUID.randomUUID(), wallet, 500_000_000L));
        PolicyView notRaised = service(false, 30)
                .changePolicy(ChangePolicyCommand.lowerCap(UUID.randomUUID(), wallet, 9_000_000_000L));

        assertThat(lowered.capLamports()).isEqualTo(500_000_000L);
        assertThat(notRaised.capLamports()).isEqualTo(500_000_000L);
        assertThat(store.findPolicy(wallet).orElseThrow().authorizedCapLamports())
                .isEqualTo(500_000_000L);
    }

    @Test
    void raisingTheCapNeedsAConsentForThisWalletCheckedByTheTxSigner() {
        String wallet = on().createWallet(activation(UUID.randomUUID())).botWalletAddress();
        String raise = ConsentText.activation(main.address())
                .purpose("RAISE_CAP")
                .botWallet(wallet)
                .capLamports("5000000000")
                .nonce(nonce())
                .text();
        UUID requestId = UUID.randomUUID();
        ChangePolicyCommand command =
                ChangePolicyCommand.raise(requestId, wallet, PolicyChange.Kind.RAISE_CAP, raise, main.sign(raise));

        PolicyView raised = on().changePolicy(command);
        PolicyView again = on().changePolicy(command);

        assertThat(raised.capLamports()).isEqualTo(5_000_000_000L);
        assertThat(raised.mode()).isEqualTo(OperationMode.SIGNED);
        assertThat(again).isEqualTo(raised);
        String forged = ConsentText.activation(main.address())
                .purpose("RAISE_CAP")
                .botWallet(wallet)
                .capLamports("9000000000")
                .nonce(nonce())
                .text();
        assertThatThrownBy(() -> on().changePolicy(ChangePolicyCommand.raise(
                        UUID.randomUUID(),
                        wallet,
                        PolicyChange.Kind.RAISE_CAP,
                        forged,
                        TestWallet.generate().sign(forged))))
                .isInstanceOfSatisfying(
                        SignerException.class, e -> assertThat(e.error()).isEqualTo(SignerError.CONSENT_INVALID));
    }

    @Test
    void raisingIsRefusedWithRealOffAndForAnUnknownWallet() {
        String wallet = on().createWallet(activation(UUID.randomUUID())).botWalletAddress();
        String raise = ConsentText.activation(main.address())
                .purpose("RAISE_CAP")
                .botWallet(wallet)
                .nonce(nonce())
                .text();

        assertThatThrownBy(() -> service(false, 30)
                        .changePolicy(ChangePolicyCommand.raise(
                                UUID.randomUUID(), wallet, PolicyChange.Kind.RAISE_CAP, raise, main.sign(raise))))
                .isInstanceOfSatisfying(
                        SignerException.class, e -> assertThat(e.error()).isEqualTo(SignerError.REAL_DISABLED));
        assertThatThrownBy(() -> on().changePolicy(
                                ChangePolicyCommand.lowerCap(UUID.randomUUID(), Base58.encode(new byte[32]), 1L)))
                .isInstanceOfSatisfying(
                        SignerException.class, e -> assertThat(e.error()).isEqualTo(SignerError.WALLET_NOT_FOUND));
    }

    @Test
    void closingMarksThePolicyClosed() {
        String wallet = on().createWallet(activation(UUID.randomUUID())).botWalletAddress();

        PolicyView closed = on().changePolicy(ChangePolicyCommand.close(UUID.randomUUID(), wallet));

        assertThat(closed.closed()).isTrue();
    }
}
