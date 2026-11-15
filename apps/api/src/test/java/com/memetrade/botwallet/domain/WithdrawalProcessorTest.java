package com.memetrade.botwallet.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.ledger.api.RealEntryKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Step 18, rules written before the code (BR4.7, NFR4.2, NFR4.6, NFR4.10, AC5.1.1): the worker builds the exact
 * withdrawal to the registered main wallet, keeps the blockhash so a retry asks for the SAME signature, never asks a
 * second signature once signed, resends only the stored transaction, fails only with both providers agreeing, and a
 * TxSigner outage leaves the withdrawal waiting with a growing wait.
 */
class WithdrawalProcessorTest {

    private static final String BOT = "GHtXQBsoZHVnNFa9YevAzFr17DJjgHXk3ycTKD5xD3Zi";
    private static final String MAIN = "4Nd1mBQtrMJVYVfKf2PJy9NZUZdTAsp7D4xWLs4gDB4T";
    private static final long SOL = 1_000_000_000L;

    private Instant now = Instant.parse("2026-11-14T12:00:00Z");
    private final Map<UUID, Withdrawal> rows = new LinkedHashMap<>();
    private final List<WithdrawalTransition> saved = new ArrayList<>();
    private final FakeChain chain = new FakeChain();
    private final List<String> signedRequests = new ArrayList<>();
    private String signerRefusal;
    private boolean signerDown;

    private final WithdrawalStore store = new WithdrawalStore() {
        @Override
        public List<Withdrawal> claimReady(Instant at, Duration lease, int limit) {
            return rows.values().stream()
                    .filter(w -> !w.state().isFinal() && !w.nextAttemptAt().isAfter(at))
                    .toList();
        }

        @Override
        public Optional<WithdrawalTarget> target(Withdrawal withdrawal) {
            return Optional.of(new WithdrawalTarget(BOT, MAIN));
        }

        @Override
        public void save(WithdrawalTransition transition) {
            rows.put(transition.withdrawal().withdrawalId(), transition.withdrawal());
            saved.add(transition);
        }
    };

    private final TxSignerPort signer = new TxSignerPort() {
        @Override
        public String createWallet(UUID requestId, String main, String message, String signature) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void lowerCap(UUID requestId, String botWalletAddress, long capLamports) {}

        @Override
        public void raise(UUID id, String bot, ConsentKind kind, String message, String signature) {}

        @Override
        public void lowerMode(UUID requestId, String botWalletAddress, String mode) {}

        @Override
        public SignOutcome signSwap(String botWalletAddress, com.memetrade.botwallet.api.SwapSigningRequest request) {
            throw new UnsupportedOperationException("a withdrawal never signs a swap");
        }

        @Override
        public boolean reachable() {
            return !signerDown;
        }

        @Override
        public SignOutcome signWithdrawal(UUID id, String bot, String tx, long lamports, long fee) {
            if (signerDown) {
                throw new SignerUnavailableException("down");
            }
            signedRequests.add(id + "|" + tx);
            return signerRefusal != null
                    ? new SignOutcome(null, null, signerRefusal)
                    : new SignOutcome("TxSig" + signedRequests.size(), "c2lnbmVk", null);
        }
    };

    private WithdrawalProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new WithdrawalProcessor(
                () -> Clock.fixed(now, ZoneOffset.UTC).instant(),
                store,
                signer,
                chain,
                new WithdrawalSettings(450, 0L, 20, Duration.ofSeconds(60)));
    }

    private Withdrawal requested() {
        Withdrawal withdrawal = Withdrawal.request(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        new WithdrawalAmount(WithdrawalKind.PARTIAL, SOL, 5_000L),
                        UUID.randomUUID(),
                        now)
                .withdrawal();
        rows.put(withdrawal.withdrawalId(), withdrawal);
        return withdrawal;
    }

    private Withdrawal current(Withdrawal withdrawal) {
        return rows.get(withdrawal.withdrawalId());
    }

    @Test
    void aRequestedWithdrawalIsSignedAndSentToTheMainWallet() {
        Withdrawal withdrawal = requested();

        processor.processReady();

        Withdrawal sent = current(withdrawal);
        assertThat(sent.state()).isEqualTo(WithdrawalState.SENT);
        assertThat(sent.signedTransaction()).isEqualTo("c2lnbmVk");
        assertThat(sent.txSignature()).isEqualTo("TxSig1");
        assertThat(chain.sent).containsExactly("c2lnbmVk");
        assertThat(signedRequests)
                .singleElement()
                .asString()
                .startsWith(withdrawal.signingRequestId().toString());
    }

    @Test
    void aRefusalOfTheTxSignerFailsTheWithdrawalAndReleasesTheReserve() {
        Withdrawal withdrawal = requested();
        signerRefusal = "DESTINATION_NOT_ALLOWED";

        processor.processReady();

        assertThat(current(withdrawal).state()).isEqualTo(WithdrawalState.FAILED);
        assertThat(saved.get(saved.size() - 1).entries())
                .extracting(e -> e.kind())
                .containsExactly(RealEntryKind.RESERVE_RELEASE);
    }

    @Test
    void aTxSignerOutageWaitsAndTheRetryAsksForTheSameSignature() {
        Withdrawal withdrawal = requested();
        signerDown = true;

        processor.processReady();
        Withdrawal waiting = current(withdrawal);
        assertThat(waiting.state()).isEqualTo(WithdrawalState.REQUESTED);
        assertThat(waiting.nextAttemptAt()).isEqualTo(now.plusSeconds(5));
        assertThat(waiting.blockhash()).isNotNull();

        signerDown = false;
        now = now.plusSeconds(5);
        processor.processReady();
        processor.processReady();

        assertThat(chain.blockhashCalls).isEqualTo(1);
        assertThat(signedRequests).hasSize(1);
        assertThat(current(withdrawal).state()).isEqualTo(WithdrawalState.SENT);
    }

    @Test
    void aFinalizedSignatureConfirmsWithTheRealFee() {
        Withdrawal withdrawal = requested();
        processor.processReady();
        chain.statuses.put(
                "TxSig1", List.of(ChainObservation.finalized("TxSig1", 4_800L), ChainObservation.unavailable()));
        now = now.plusSeconds(61);

        processor.processReady();

        assertThat(current(withdrawal).state()).isEqualTo(WithdrawalState.CONFIRMED);
        assertThat(saved.get(saved.size() - 1).entries())
                .extracting(e -> e.kind() + ":" + e.lamports())
                .containsExactly("WITHDRAWAL:-" + SOL, "NETWORK_FEE:-4800", "RESERVE_RELEASE:" + (SOL + 5_000L));
    }

    @Test
    void anAmbiguousWithdrawalNeverAsksASecondSignatureAndResendsTheStoredTransaction() {
        Withdrawal withdrawal = requested();
        processor.processReady();
        now = now.plusSeconds(61);
        processor.processReady();
        assertThat(current(withdrawal).state()).isEqualTo(WithdrawalState.AMBIGUOUS);

        now = now.plusSeconds(600);
        processor.processReady();

        assertThat(signedRequests).hasSize(1);
        assertThat(chain.sent).allMatch("c2lnbmVk"::equals).hasSizeGreaterThan(1);
        assertThat(current(withdrawal).state()).isEqualTo(WithdrawalState.AMBIGUOUS);
    }

    @Test
    void itFailsOnlyWhenBothProvidersSeeTheBlockhashExpiredWithoutTheSignature() {
        Withdrawal withdrawal = requested();
        processor.processReady();
        chain.statuses.put("TxSig1", List.of(ChainObservation.notFound(1_001L), ChainObservation.notFound(1_001L)));
        now = now.plusSeconds(61);

        processor.processReady();

        assertThat(current(withdrawal).state()).isEqualTo(WithdrawalState.FAILED);
        assertThat(current(withdrawal).failureCode()).isEqualTo("BLOCKHASH_EXPIRED");
    }

    @Test
    void withTheNetworkDownNothingIsLostAndTheWithdrawalWaits() {
        Withdrawal withdrawal = requested();
        chain.down = true;

        processor.processReady();

        assertThat(current(withdrawal).state()).isEqualTo(WithdrawalState.REQUESTED);
        assertThat(current(withdrawal).attempts()).isEqualTo(1);
        assertThat(signedRequests).isEmpty();
    }

    @Test
    void anUnsignedWithdrawalFailsAfter24Hours() {
        Withdrawal withdrawal = requested();
        signerDown = true;
        now = now.plus(Duration.ofHours(24));

        processor.processReady();

        assertThat(current(withdrawal).state()).isEqualTo(WithdrawalState.FAILED);
        assertThat(current(withdrawal).failureCode()).isEqualTo("UNSIGNED_EXPIRED");
    }
}
