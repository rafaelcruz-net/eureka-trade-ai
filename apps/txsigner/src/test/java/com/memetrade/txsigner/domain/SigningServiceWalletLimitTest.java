package com.memetrade.txsigner.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.txsigner.codec.Base58;
import com.memetrade.txsigner.domain.kms.KeyTags;
import com.memetrade.txsigner.policy.OperationMode;
import com.memetrade.txsigner.policy.PolicyRefusal;
import com.memetrade.txsigner.policy.WithdrawalContext;
import com.memetrade.txsigner.testsupport.InMemorySignerStore;
import com.memetrade.txsigner.testsupport.KmsSimulator;
import com.memetrade.txsigner.testsupport.MutableClock;
import com.memetrade.txsigner.testsupport.RecordingEvents;
import com.memetrade.txsigner.testsupport.SwapTx;
import com.memetrade.txsigner.testsupport.TestWallet;
import com.memetrade.txsigner.testsupport.TxBytes;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Step 31 of the U1, written BEFORE the code (NFR1.9 second half, review R-10, team.md mandatory test "saque só para a
 * carteira principal ... continua disponível"): between the api and the TxSigner at most 60 signing requests per minute
 * per bot wallet. The 61st is refused with its own reason {@code WALLET_RATE_LIMITED}, recorded in the audit event and
 * WITHOUT a KMS call; the count of one wallet never touches another's; a WITHDRAWAL (always to the registered main
 * wallet) is NEVER blocked by the limit and never consumes it; a replay of a decided request is not a new request.
 */
class SigningServiceWalletLimitTest {

    private static final long AMOUNT = 400_000_000L;
    private static final long FEE = 5_001L;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-18T12:00:00Z"));
    private final KmsSimulator kms = new KmsSimulator();
    private final InMemorySignerStore store = new InMemorySignerStore();
    private final RecordingEvents events = new RecordingEvents();
    private final TestWallet main = TestWallet.generate();
    private Wallet first;
    private Wallet second;
    private SigningService service;

    private record Wallet(String address, byte[] publicKey) {}

    @BeforeEach
    void createWallets() {
        first = wallet("aa");
        second = wallet("bb");
        SignerSettings settings =
                new SignerSettings(true, "LOCALNET", 10_000_000_000L, 100_000L, 1_000, 5_000L, 30, 1_000_000L);
        service = new SigningService(settings, clock, kms, store, events, () -> RecoveryPhase.NORMAL);
    }

    private Wallet wallet(String tenantHash) {
        String keyRef = kms.createKey(new KeyTags(tenantHash, UUID.randomUUID().toString(), "LOCALNET"));
        byte[] publicKey = kms.publicKey(keyRef);
        String address = Base58.encode(publicKey);
        store.put(new WalletPolicy(
                address,
                keyRef,
                main.address(),
                2_000_000_000L,
                OperationMode.AUTO_FULL,
                false,
                false,
                false,
                UUID.randomUUID(),
                100_000L,
                clock.instant(),
                clock.instant()));
        return new Wallet(address, publicKey);
    }

    private SignCommand swap(Wallet wallet, UUID id) {
        SwapTx tx = new SwapTx(wallet.publicKey(), main);
        return SignCommand.swap(id, wallet.address(), tx.base64(), tx.context());
    }

    private SignCommand withdrawal(Wallet wallet, UUID id) {
        byte[] message = TxBytes.withdrawal(wallet.publicKey(), main.publicKey(), AMOUNT, 450, 2L)
                .build();
        String transaction = Base64.getEncoder().encodeToString(TxBytes.unsigned(message, 1));
        return new SignCommand(
                id, wallet.address(), OperationKind.WITHDRAWAL, transaction, new WithdrawalContext(AMOUNT, FEE));
    }

    private void spendTheMinute(Wallet wallet) {
        for (int i = 0; i < WalletRequestLimiter.REQUESTS_PER_MINUTE; i++) {
            SigningOutcome outcome = service.sign(swap(wallet, UUID.randomUUID()));
            assertThat(outcome.signed())
                    .as("request " + (i + 1) + ": " + outcome.refusal())
                    .isTrue();
        }
    }

    @Test
    void theSixtyFirstRequestInAMinuteIsRefusedWithItsOwnReasonRecordedAndWithoutCallingTheKms() {
        spendTheMinute(first);
        int kmsCalls = kms.signCalls();
        UUID sixtyFirst = UUID.randomUUID();

        SigningOutcome outcome = service.sign(swap(first, sixtyFirst));

        assertThat(outcome.signed()).isFalse();
        assertThat(outcome.refusal()).isEqualTo(PolicyRefusal.WALLET_RATE_LIMITED);
        assertThat(kms.signCalls())
                .as("the KMS is never called above the limit")
                .isEqualTo(kmsCalls);
        SignerEvent recorded = events.all().get(events.all().size() - 1);
        assertThat(recorded.type()).isEqualTo(SignerEvent.Type.SIGNING_REFUSED);
        assertThat(recorded.reason()).isEqualTo("WALLET_RATE_LIMITED");
        assertThat(recorded.botWalletAddress()).isEqualTo(first.address());
        assertThat(recorded.requestId()).isEqualTo(sixtyFirst);
        assertThat(store.findSigningRecord(sixtyFirst))
                .as("a transient refusal burns no signingRequestId")
                .isEmpty();
    }

    @Test
    void theSameRequestIsSignedOnceTheOldestRequestLeavesTheWindow() {
        spendTheMinute(first);
        UUID id = UUID.randomUUID();
        SignCommand command = swap(first, id);
        assertThat(service.sign(command).refusal()).isEqualTo(PolicyRefusal.WALLET_RATE_LIMITED);

        clock.advance(Duration.ofSeconds(59));
        assertThat(service.sign(command).refusal()).isEqualTo(PolicyRefusal.WALLET_RATE_LIMITED);
        clock.advance(Duration.ofSeconds(1));
        SigningOutcome later = service.sign(command);

        assertThat(later.signed()).as(String.valueOf(later.refusal())).isTrue();
        assertThat(store.findSigningRecord(id).orElseThrow().decision()).isEqualTo(SigningRecord.Decision.SIGNED);
    }

    @Test
    void theCountOfOneWalletIsIndependentOfAnother() {
        spendTheMinute(first);
        assertThat(service.sign(swap(first, UUID.randomUUID())).refusal()).isEqualTo(PolicyRefusal.WALLET_RATE_LIMITED);

        SigningOutcome other = service.sign(swap(second, UUID.randomUUID()));

        assertThat(other.signed()).as(String.valueOf(other.refusal())).isTrue();
    }

    @Test
    void aWithdrawalToTheMainWalletIsNeverBlockedByTheLimitAndNeverConsumesIt() {
        spendTheMinute(first);
        assertThat(service.sign(swap(first, UUID.randomUUID())).refusal()).isEqualTo(PolicyRefusal.WALLET_RATE_LIMITED);

        for (int i = 0; i < 2 * WalletRequestLimiter.REQUESTS_PER_MINUTE; i++) {
            SigningOutcome withdrawal = service.sign(withdrawal(first, UUID.randomUUID()));
            assertThat(withdrawal.signed())
                    .as("withdrawal " + (i + 1) + ": " + withdrawal.refusal())
                    .isTrue();
        }
        for (int i = 0; i < 2 * WalletRequestLimiter.REQUESTS_PER_MINUTE; i++) {
            assertThat(service.sign(withdrawal(second, UUID.randomUUID())).signed())
                    .isTrue();
        }

        assertThat(service.sign(swap(second, UUID.randomUUID())).signed())
                .as("120 withdrawals of the second wallet consumed none of its 60 requests")
                .isTrue();
    }

    @Test
    void aReplayOfADecidedRequestIsNotANewRequest() {
        UUID id = UUID.randomUUID();
        SignCommand command = swap(first, id);
        SigningOutcome signed = service.sign(command);
        for (int i = 1; i < WalletRequestLimiter.REQUESTS_PER_MINUTE; i++) {
            service.sign(swap(first, UUID.randomUUID()));
        }

        SigningOutcome replay = service.sign(command);

        assertThat(replay).isEqualTo(signed);
    }
}
