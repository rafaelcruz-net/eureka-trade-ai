package com.memetrade.identity.domain.login;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.memetrade.audit.api.ActorType;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.domain.idempotency.IdempotencyService;
import com.memetrade.identity.domain.idempotency.InMemoryIdempotencyStore;
import com.memetrade.identity.domain.login.ChallengeService.IssuedChallenge;
import com.memetrade.identity.domain.login.LoginVerificationService.VerifyCommand;
import com.memetrade.identity.domain.session.Account;
import com.memetrade.identity.domain.session.FakeAccessTokens;
import com.memetrade.identity.domain.session.InMemorySessionRepository;
import com.memetrade.identity.domain.session.RefreshTokens;
import com.memetrade.identity.domain.session.SessionService;
import com.memetrade.identity.domain.session.SessionSettings;
import com.memetrade.identity.domain.signature.Ed25519SignatureVerifier;
import com.memetrade.shared.wallet.WalletAddress;
import com.memetrade.testsupport.MutableClock;
import com.memetrade.testsupport.RecordingAuditPublisher;
import com.memetrade.testsupport.TestWallet;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u1")
class LoginVerificationServiceTest {

    private final MutableClock clock = new MutableClock();
    private final InMemoryLoginChallengeRepository challenges = new InMemoryLoginChallengeRepository();
    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final RecordingAuditPublisher audit = new RecordingAuditPublisher();
    private final Map<String, Account> accounts = new HashMap<>();
    private final Set<String> admins = new HashSet<>();
    private final LoginSettings settings =
            new LoginSettings(URI.create("http://localhost:3000"), "solana:mainnet", Duration.ofMinutes(5), admins);
    private final ChallengeService challengeService = new ChallengeService(
            challenges,
            new NonceGenerator(new SecureRandom()),
            new IdempotencyService(new InMemoryIdempotencyStore(), clock, Duration.ofHours(24)),
            settings,
            clock,
            new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    private final LoginVerificationService service = new LoginVerificationService(
            challenges,
            address -> Optional.ofNullable(accounts.get(address)),
            new SessionService(
                    sessions,
                    new FakeAccessTokens(),
                    new RefreshTokens(new SecureRandom()),
                    new SessionSettings(Duration.ofMinutes(15), Duration.ofHours(8), Duration.ofHours(12)),
                    audit,
                    clock),
            new Ed25519SignatureVerifier(),
            audit,
            settings,
            clock);
    private final TestWallet wallet = TestWallet.generate();
    private final UUID tenant = UUID.randomUUID();

    @Test
    void acceptedVerificationConsumesTheNonceOpensASessionAndRecordsLoginSucceeded() {
        registerBetaUser(wallet, true, true);
        IssuedChallenge issued = issue(wallet);

        VerificationOutcome outcome = service.verify(signed(wallet, issued.message()));

        assertThat(outcome).isInstanceOf(VerificationOutcome.Accepted.class);
        assertThat(state(issued)).isEqualTo(ChallengeState.CONSUMED);
        assertThat(sessions.size()).isEqualTo(1);
        AuditEventCommand event = audit.withAction(AuditActions.LOGIN_SUCCEEDED).getFirst();
        assertThat(event.tenantId()).contains(tenant);
        assertThat(event.actorType()).isEqualTo(ActorType.PERSON);
        assertThat(audit.withAction(AuditActions.LOGIN_REJECTED)).isEmpty();
    }

    @Test
    void aReusedNonceIsRejectedWithoutAnyStateChange() {
        registerBetaUser(wallet, true, true);
        IssuedChallenge issued = issue(wallet);
        service.verify(signed(wallet, issued.message()));

        assertThat(rejection(service.verify(signed(wallet, issued.message()))))
                .isEqualTo(RejectionReason.NONCE_ALREADY_USED);
        assertThat(state(issued)).isEqualTo(ChallengeState.CONSUMED);
        assertThat(sessions.size()).isEqualTo(1);
        assertThat(audit.withAction(AuditActions.LOGIN_REJECTED)).hasSize(1);
    }

    @Test
    void aSignatureSentAfterExpiryIsRejectedAndTheChallengeBecomesExpired() {
        registerBetaUser(wallet, true, true);
        IssuedChallenge issued = issue(wallet);
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThat(rejection(service.verify(signed(wallet, issued.message())))).isEqualTo(RejectionReason.EXPIRED);
        assertThat(state(issued)).isEqualTo(ChallengeState.EXPIRED);
        assertThat(rejection(service.verify(signed(wallet, issued.message()))))
                .as("EXPIRED is final")
                .isEqualTo(RejectionReason.NONCE_ALREADY_USED);
    }

    @Test
    void verificationExactlyAtTheExpiryInstantIsStillAccepted() {
        registerBetaUser(wallet, true, true);
        IssuedChallenge issued = issue(wallet);
        clock.advance(Duration.ofMinutes(5));

        assertThat(service.verify(signed(wallet, issued.message()))).isInstanceOf(VerificationOutcome.Accepted.class);
    }

    @Test
    void anAlteredButWellFormedMessageIsRejectedAndConsumesTheNonce() {
        registerBetaUser(wallet, true, true);
        IssuedChallenge issued = issue(wallet);
        String altered = issued.message().replace("Chain: solana:mainnet", "Chain: solana:devnet");

        assertThat(rejection(service.verify(signed(wallet, altered)))).isEqualTo(RejectionReason.MESSAGE_MISMATCH);
        assertThat(state(issued)).isEqualTo(ChallengeState.CONSUMED);
        assertThat(rejection(service.verify(signed(wallet, issued.message()))))
                .as("the nonce cannot be retried after a mismatch")
                .isEqualTo(RejectionReason.NONCE_ALREADY_USED);
    }

    @Test
    void aSignatureFromAnotherWalletIsRejectedAndConsumesTheNonce() {
        registerBetaUser(wallet, true, true);
        IssuedChallenge issued = issue(wallet);
        TestWallet attacker = TestWallet.generate();
        VerifyCommand forged = new VerifyCommand(
                wallet.address(), issued.message(), attacker.sign(issued.message()), "PHANTOM", "corr");

        assertThat(rejection(service.verify(forged))).isEqualTo(RejectionReason.WRONG_SIGNER);
        assertThat(state(issued)).isEqualTo(ChallengeState.CONSUMED);
        assertThat(sessions.size()).isZero();
    }

    @Test
    void aMessageWhoseNonceMatchesNoChallengeIsRejectedWithoutTouchingAnyChallenge() {
        registerBetaUser(wallet, true, true);
        IssuedChallenge issued = issue(wallet);
        String unknown = issued.message().replace(issued.nonce(), "ZZZZZZZZZZZZZZZZZZZZZZ");

        assertThat(rejection(service.verify(signed(wallet, unknown)))).isEqualTo(RejectionReason.UNKNOWN_CHALLENGE);
        assertThat(state(issued)).isEqualTo(ChallengeState.ISSUED);
    }

    @Test
    void aMessageOutsideTheTemplateIsMalformed() {
        assertThat(rejection(service.verify(signed(wallet, "sign in please"))))
                .isEqualTo(RejectionReason.MALFORMED_MESSAGE);
        assertThat(audit.withAction(AuditActions.LOGIN_REJECTED)).hasSize(1);
    }

    @Test
    void theAdministratorWalletIsForbiddenOnTheGuestEntry() {
        registerBetaUser(wallet, true, true);
        admins.add(wallet.address());
        IssuedChallenge issued = issue(wallet);

        VerificationOutcome outcome = service.verify(signed(wallet, issued.message()));

        assertThat(rejection(outcome)).isEqualTo(RejectionReason.ADMIN_WALLET);
        assertThat(((VerificationOutcome.Rejected) outcome).forbiddenRole()).isTrue();
        assertThat(state(issued)).isEqualTo(ChallengeState.CONSUMED);
        assertThat(sessions.size()).isZero();
    }

    @Test
    void aWalletWithoutAnActiveAccountOrWithASuspendedTenantIsRejected() {
        IssuedChallenge noAccount = issue(wallet);
        assertThat(rejection(service.verify(signed(wallet, noAccount.message()))))
                .isEqualTo(RejectionReason.UNKNOWN_ACCOUNT);
        assertThat(state(noAccount)).isEqualTo(ChallengeState.CONSUMED);

        registerBetaUser(wallet, true, false);
        IssuedChallenge suspended = issue(wallet);
        assertThat(rejection(service.verify(signed(wallet, suspended.message()))))
                .isEqualTo(RejectionReason.UNKNOWN_ACCOUNT);

        registerBetaUser(wallet, false, true);
        IssuedChallenge disabled = issue(wallet);
        assertThat(rejection(service.verify(signed(wallet, disabled.message()))))
                .isEqualTo(RejectionReason.UNKNOWN_ACCOUNT);
        assertThat(sessions.size()).isZero();
    }

    @Test
    void aChallengeIssuedToAnotherWalletIsAMismatch() {
        TestWallet other = TestWallet.generate();
        registerBetaUser(other, true, true);
        IssuedChallenge issuedToWallet = issue(wallet);

        VerifyCommand command = new VerifyCommand(
                other.address(), issuedToWallet.message(), other.sign(issuedToWallet.message()), "SOLFLARE", "c");

        assertThat(rejection(service.verify(command))).isEqualTo(RejectionReason.MESSAGE_MISMATCH);
    }

    @Test
    void eachRejectionRecordsExactlyOneAnonymousEventWithReasonAndMaskedActor() {
        IssuedChallenge issued = issue(wallet);
        clock.advance(Duration.ofMinutes(6));

        service.verify(signed(wallet, issued.message()));

        List<AuditEventCommand> rejected = audit.withAction(AuditActions.LOGIN_REJECTED);
        assertThat(rejected).hasSize(1);
        AuditEventCommand event = rejected.getFirst();
        assertThat(event.tenantId()).isEmpty();
        assertThat(event.actorType()).isEqualTo(ActorType.ANONYMOUS);
        assertThat(event.actor()).isEqualTo(WalletAddress.mask(wallet.address()));
        assertThat(event.details()).containsEntry("reason", "EXPIRED").containsEntry("walletAddress", wallet.address());
        assertThat(String.join(",", event.details().values())).doesNotContain(issued.nonce());
        assertThat(event.correlationId()).isEqualTo("corr");
    }

    @Test
    void twoSimultaneousVerificationsOfTheSameNonceOpenASingleSession() throws Exception {
        registerBetaUser(wallet, true, true);
        IssuedChallenge issued = issue(wallet);
        VerifyCommand command = signed(wallet, issued.message());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<VerificationOutcome> call = () -> service.verify(command);
            List<Future<VerificationOutcome>> results = pool.invokeAll(List.of(call, call));

            long accepted = 0;
            for (Future<VerificationOutcome> result : results) {
                accepted += result.get() instanceof VerificationOutcome.Accepted ? 1 : 0;
            }
            assertThat(accepted).isEqualTo(1);
            assertThat(sessions.size()).isEqualTo(1);
            assertThat(audit.withAction(AuditActions.LOGIN_REJECTED)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theCommandNeverPrintsTheMessageOrSignature() {
        VerifyCommand command = signed(wallet, "Nonce: secret-nonce-value");

        assertThat(command.toString()).doesNotContain("secret-nonce-value").doesNotContain(command.signature());
    }

    private IssuedChallenge issue(TestWallet who) {
        return challengeService.issue(WalletAddress.of(who.address()), new IdempotencyKey(UUID.randomUUID()), "d");
    }

    private static VerifyCommand signed(TestWallet who, String message) {
        return new VerifyCommand(who.address(), message, who.sign(message), "PHANTOM", "corr");
    }

    private void registerBetaUser(TestWallet who, boolean userActive, boolean tenantActive) {
        accounts.put(who.address(), new Account(UUID.randomUUID(), tenant, Role.BETA_USER, userActive, tenantActive));
    }

    private ChallengeState state(IssuedChallenge issued) {
        return challenges.findByNonce(issued.nonce()).orElseThrow().state();
    }

    private static RejectionReason rejection(VerificationOutcome outcome) {
        assertThat(outcome).isInstanceOf(VerificationOutcome.Rejected.class);
        return ((VerificationOutcome.Rejected) outcome).reason();
    }
}
