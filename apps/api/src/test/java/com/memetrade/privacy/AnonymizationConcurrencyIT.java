package com.memetrade.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.infra.LockOrder;
import com.memetrade.identity.domain.login.LoginChallengeRepository;
import com.memetrade.identity.domain.login.LoginSettings;
import com.memetrade.identity.domain.login.LoginVerificationService;
import com.memetrade.identity.domain.login.VerificationOutcome;
import com.memetrade.identity.domain.session.AccessTokens;
import com.memetrade.identity.domain.session.Account;
import com.memetrade.identity.domain.session.AccountRepository;
import com.memetrade.identity.domain.session.RefreshOutcome;
import com.memetrade.identity.domain.session.RefreshTokens;
import com.memetrade.identity.domain.session.Session;
import com.memetrade.identity.domain.session.SessionAuthView;
import com.memetrade.identity.domain.session.SessionRepository;
import com.memetrade.identity.domain.session.SessionService;
import com.memetrade.identity.domain.session.SessionSettings;
import com.memetrade.identity.domain.session.SessionState;
import com.memetrade.identity.domain.signature.Ed25519SignatureVerifier;
import com.memetrade.privacy.domain.anonymization.AnonymizationResult;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.Concurrently;
import com.memetrade.testsupport.TestWallet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Passo 2 of u5-anonymization, written BEFORE the code (BR5.6; NFR5.5, NFR5.6; AC6.2.6): two anonymizations of the same
 * request, a login held between its read of the account and the opening of its session, a refresh held after its
 * session lock and a cycle of a bot holding the bot lock, each against an anonymization. Synchronisation by test pause
 * points ({@link CountDownLatch}), never by sleeping. After the commit of the anonymization there is never an ACTIVE
 * session nor a new order of the person, and exactly one ACCOUNT_ANONYMIZED.
 */
class AnonymizationConcurrencyIT extends AbstractAnonymizationIT {

    private static final long WAIT_SECONDS = 20;

    @Autowired
    private LoginChallengeRepository challenges;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private AccessTokens accessTokens;

    @Autowired
    private RefreshTokens refreshTokens;

    @Autowired
    private SessionSettings sessionSettings;

    @Autowired
    private Ed25519SignatureVerifier signatures;

    @Autowired
    private AuditEventPublisher audit;

    @Autowired
    private LoginSettings loginSettings;

    @Autowired
    private LockOrder lockOrder;

    @Test
    void twoAnonymizationsOfTheSameRequestMakeOneChangeAndOneEvent() throws Exception {
        Deletion deletion = deletionInProgress();

        List<Concurrently.Outcome<AnonymizationResult>> outcomes = Concurrently.run(
                () -> anonymize(deletion.admin(), deletion.requestId(), UUID.randomUUID()),
                () -> anonymize(deletion.admin(), deletion.requestId(), UUID.randomUUID()));

        assertThat(outcomes).noneMatch(Concurrently.Outcome::failed);
        assertThat(outcomes)
                .extracting(outcome -> outcome.value().alreadyAnonymized())
                .containsExactlyInAnyOrder(true, false);
        assertThat(outcomes)
                .extracting(outcome -> outcome.value().anonymizedAt())
                .containsOnly(clock.instant());
        assertThat(anonymizedEvents(deletion.userId())).isEqualTo(1);
        assertThat(status(deletion.requestId())).isEqualTo("DONE");
    }

    @Test
    void twoCallsWithTheSameKeyAnswerTheSameResult() throws Exception {
        Deletion deletion = deletionInProgress();
        UUID key = UUID.randomUUID();

        List<Concurrently.Outcome<AnonymizationResult>> outcomes = Concurrently.run(
                () -> anonymize(deletion.admin(), deletion.requestId(), key),
                () -> anonymize(deletion.admin(), deletion.requestId(), key));

        assertThat(outcomes).noneMatch(Concurrently.Outcome::failed);
        assertThat(outcomes.get(0).value()).isEqualTo(outcomes.get(1).value());
        assertThat(outcomes.get(0).value().alreadyAnonymized()).isFalse();
        assertThat(anonymizedEvents(deletion.userId())).isEqualTo(1);
    }

    @Test
    void aLoginHeldBetweenItsReadAndItsSessionNeverLeavesAnActiveSession() throws Exception {
        Deletion deletion = deletionInProgress();
        TestWallet wallet = deletion.guest().wallet();
        String message =
                new AuthFlow(mvc, json).challenge(wallet).get("message").asText();
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LoginVerificationService login = new LoginVerificationService(
                challenges,
                new PausingAccounts(accountRepository, read, release),
                sessionService(sessionRepository),
                signatures,
                audit,
                loginSettings,
                clock);

        CompletableFuture<VerificationOutcome> loginRun = CompletableFuture.supplyAsync(() -> transaction()
                .execute(status -> login.verify(new LoginVerificationService.VerifyCommand(
                        wallet.address(), message, wallet.sign(message), "PHANTOM", "corr-u5-login"))));
        assertThat(read.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<AnonymizationResult> anonymizationRun =
                CompletableFuture.supplyAsync(() -> anonymize(deletion));
        waitUntilBlocked();
        release.countDown();

        VerificationOutcome outcome = loginRun.get(WAIT_SECONDS, TimeUnit.SECONDS);
        AnonymizationResult result = anonymizationRun.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(result.alreadyAnonymized()).isFalse();
        assertThat(accountStatus(deletion.userId())).isEqualTo("ANONYMIZED");
        assertThat(activeSessions(deletion.userId()))
                .as("whatever the login answered (%s), no session of the person is ACTIVE", outcome)
                .isZero();
    }

    @Test
    void aLoginAfterTheCommitFindsNoAccountForTheAddress() throws Exception {
        Deletion deletion = deletionInProgress();
        anonymize(deletion);

        MockHttpServletResponse login =
                new AuthFlow(mvc, json).login(deletion.guest().wallet());

        assertThat(login.getStatus()).isEqualTo(401);
        assertThat(activeSessions(deletion.userId())).isZero();
    }

    @Test
    void aRefreshHeldAfterItsSessionLockNeverLeavesAnActiveSession() throws Exception {
        Deletion deletion = deletionInProgress();
        MockHttpServletResponse login =
                new AuthFlow(mvc, json).login(deletion.guest().wallet());
        String refreshToken = AuthFlow.refreshToken(login);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SessionService refresh = sessionService(new PausingSessions(sessionRepository, locked, release));

        CompletableFuture<RefreshOutcome> refreshRun = CompletableFuture.supplyAsync(
                () -> transaction().execute(status -> refresh.refresh(refreshToken, "corr-u5-refresh")));
        assertThat(locked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<AnonymizationResult> anonymizationRun =
                CompletableFuture.supplyAsync(() -> anonymize(deletion));
        waitUntilBlocked();
        release.countDown();

        RefreshOutcome outcome = refreshRun.get(WAIT_SECONDS, TimeUnit.SECONDS);
        anonymizationRun.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(activeSessions(deletion.userId()))
                .as("whatever the refresh answered (%s), no session of the person is ACTIVE", outcome)
                .isZero();
    }

    @Test
    void aRefreshAfterTheCommitIsRefused() throws Exception {
        Deletion deletion = deletionInProgress();
        MockHttpServletResponse login =
                new AuthFlow(mvc, json).login(deletion.guest().wallet());
        String refreshToken = AuthFlow.refreshToken(login);
        anonymize(deletion);

        RefreshOutcome outcome = transaction()
                .execute(status -> sessionService(sessionRepository).refresh(refreshToken, "corr"));

        assertThat(outcome).isInstanceOf(RefreshOutcome.Refused.class);
        assertThat(activeSessions(deletion.userId())).isZero();
    }

    @Test
    void aCycleHoldingTheBotLockMakesTheAnonymizationWaitAndTheBotIsStoppedAfter() throws Exception {
        Deletion deletion = deletionInProgress();
        UUID botId = seedBot(deletion.guest().principal(), "ACTIVE");
        TenantScope scope = new TenantScope(deletion.tenantId(), deletion.userId());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<String> cycle =
                CompletableFuture.supplyAsync(() -> transaction().execute(status -> {
                    Bot bot = lockOrder.lockBot(botId, scope).orElseThrow();
                    locked.countDown();
                    await(release);
                    return bot.status().state().name();
                }));
        assertThat(locked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<AnonymizationResult> anonymizationRun =
                CompletableFuture.supplyAsync(() -> anonymize(deletion));
        waitUntilBlocked();
        assertThat(anonymizationRun)
                .as("the anonymization waits for the bot lock")
                .isNotDone();
        release.countDown();

        assertThat(cycle.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo("ACTIVE");
        assertThat(anonymizationRun.get(WAIT_SECONDS, TimeUnit.SECONDS).botsStopped())
                .isEqualTo(1);
        String afterwards = transaction().execute(status -> lockOrder
                .lockBot(botId, scope)
                .orElseThrow()
                .status()
                .state()
                .name());
        assertThat(afterwards)
                .as("the next cycle rereads STOPPED under the lock and does nothing")
                .isEqualTo("STOPPED");
        assertThat(jdbc.sql("select count(*) from paper_order where bot_id = :b")
                        .param("b", botId)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    // ------------------------------------------------------------------ pause points

    private SessionService sessionService(SessionRepository repository) {
        return new SessionService(repository, accessTokens, refreshTokens, sessionSettings, audit, clock);
    }

    /** Waits until another backend of the test database is blocked on a lock (pg_stat_activity), at most 10 s. */
    private void waitUntilBlocked() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long waiting = connect(SUPERUSER)
                    .sql("select count(*) from pg_stat_activity where wait_event_type = 'Lock'")
                    .query(Long.class)
                    .single();
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("the anonymization never waited for the held lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("the pause point was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /** The login reads the account, signals, and waits before going on (before the lock and the session). */
    private record PausingAccounts(AccountRepository delegate, CountDownLatch read, CountDownLatch release)
            implements AccountRepository {
        @Override
        public Optional<Account> findByWallet(String walletAddress) {
            Optional<Account> account = delegate.findByWallet(walletAddress);
            read.countDown();
            await(release);
            return account;
        }

        @Override
        public boolean lockActiveForSession(UUID userId) {
            return delegate.lockActiveForSession(userId);
        }
    }

    /** The refresh locks its session, signals, and waits before reading the account. */
    private record PausingSessions(SessionRepository delegate, CountDownLatch locked, CountDownLatch release)
            implements SessionRepository {
        @Override
        public void insert(Session session) {
            delegate.insert(session);
        }

        @Override
        public Optional<Session> findById(UUID sessionId) {
            return delegate.findById(sessionId);
        }

        @Override
        public Optional<Session> findByRefreshDigestForUpdate(String digest) {
            Optional<Session> session = delegate.findByRefreshDigestForUpdate(digest);
            locked.countDown();
            await(release);
            return session;
        }

        @Override
        public Optional<UUID> findSessionOfUsedDigest(String digest) {
            return delegate.findSessionOfUsedDigest(digest);
        }

        @Override
        public boolean rotate(
                UUID sessionId, String oldDigest, String newDigest, Instant newRefreshExpiresAt, Instant now) {
            return delegate.rotate(sessionId, oldDigest, newDigest, newRefreshExpiresAt, now);
        }

        @Override
        public boolean close(UUID sessionId, SessionState target, Instant now) {
            return delegate.close(sessionId, target, now);
        }

        @Override
        public Optional<SessionAuthView> findAuthView(UUID sessionId) {
            return delegate.findAuthView(sessionId);
        }

        @Override
        public Optional<SessionAuthView> findAuthViewSharingAccount(UUID sessionId) {
            return delegate.findAuthViewSharingAccount(sessionId);
        }
    }
}
