package com.memetrade.privacy.domain.anonymization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.bots.api.BotOwnerDataPort;
import com.memetrade.feedback.api.FeedbackOwnerDataPort;
import com.memetrade.identity.api.AccountAnonymizationPort;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.domain.idempotency.IdempotencyService;
import com.memetrade.identity.domain.idempotency.InMemoryIdempotencyStore;
import com.memetrade.privacy.domain.request.Actor;
import com.memetrade.privacy.domain.request.DataRequestEvents;
import com.memetrade.privacy.domain.request.DataRequestStatus;
import com.memetrade.privacy.domain.request.DataRequestStore;
import com.memetrade.privacy.domain.request.DataRequestType;
import com.memetrade.privacy.domain.request.DataSubjectRequest;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

/**
 * Passo 2 of u5-anonymization, written BEFORE the service (Testing Contract: personal data, tests first from the
 * business rules). BR5.1: each refusal, in the order of security-design.md NFR2.1 and with nothing changed; BR5.2: J2,
 * J3, J1, J6 and the conclusion in this order; BR5.5: a repetition answers the same result without a write or an event;
 * NFR5.4 and NFR8.4: a lock timeout is 503 LOCK_TIMEOUT and every failure is observed with its closed reason.
 */
@Tag("u5-anonymization")
class AnonymizationServiceTest {

    private static final Duration RESPONSE_TIME = Duration.ofDays(15);

    private final MutableClock clock = new MutableClock();
    private final List<String> calls = new ArrayList<>();
    private final Store store = new Store();
    private final Accounts accounts = new Accounts();
    private final Bots bots = new Bots();
    private final Feedback feedback = new Feedback();
    private final Events events = new Events();
    private final RequestEvents requestEvents = new RequestEvents();
    private final Outcomes outcomes = new Outcomes();
    private final AnonymizationService service = new AnonymizationService(
            store,
            new IdempotencyService(new InMemoryIdempotencyStore(), clock, Duration.ofHours(24)),
            accounts,
            bots,
            feedback,
            events,
            requestEvents,
            outcomes,
            () -> calls.add("TIMEOUT"),
            clock);
    private final Principal admin = new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.ADMIN);
    private final UUID tenant = UUID.randomUUID();
    private final UUID person = UUID.randomUUID();

    // ------------------------------------------------------------------ BR5.1: the refusals, nothing changes

    @Test
    void aConfirmationOtherThanAnonimizarIs422AndNothingIsCalled() {
        DataSubjectRequest request = deletionInProgress();

        for (String typed : new String[] {null, "", "anonimizar", "ANONIMIZAR ", "Anonimizar", "EXCLUIR"}) {
            assertRefused(() -> service.anonymize(command(request, typed)), ErrorCode.CONFIRMATION_REQUIRED, 422);
        }
        assertNothingChanged(request);
    }

    @Test
    void anUnknownRequestIsNotFound() {
        assertRefused(
                () -> service.anonymize(new AnonymizationService.Anonymize(
                        admin, UUID.randomUUID(), AnonymizationService.CONFIRMATION, key())),
                ErrorCode.NOT_FOUND,
                404);
        assertThat(outcomes.refused).isEqualTo(1);
    }

    @Test
    void aRequestThatIsNotADeletionIsNotEligible() {
        for (DataRequestType type :
                List.of(DataRequestType.ACCESS, DataRequestType.CORRECTION, DataRequestType.OTHER)) {
            DataSubjectRequest request = store.add(DataSubjectRequest.fromAccount(
                            UUID.randomUUID(), tenant, person, type, "x", clock.instant(), RESPONSE_TIME)
                    .inProgress(false));
            accounts.add(person, tenant, Role.BETA_USER);

            assertRefused(() -> service.anonymize(command(request)), ErrorCode.REQUEST_NOT_ELIGIBLE, 409);
            assertNothingChanged(request);
        }
    }

    @Test
    void aDeletionThatIsNotInProgressIsNotEligible() {
        accounts.add(person, tenant, Role.BETA_USER);
        DataSubjectRequest received = store.add(DataSubjectRequest.fromAccount(
                UUID.randomUUID(), tenant, person, DataRequestType.DELETION, "x", clock.instant(), RESPONSE_TIME));
        DataSubjectRequest refused = store.add(DataSubjectRequest.fromAccount(
                        UUID.randomUUID(),
                        tenant,
                        person,
                        DataRequestType.DELETION,
                        "x",
                        clock.instant(),
                        RESPONSE_TIME)
                .inProgress(false)
                .refused("Motivo sintético", clock.instant()));

        assertRefused(() -> service.anonymize(command(received)), ErrorCode.REQUEST_NOT_ELIGIBLE, 409);
        assertRefused(() -> service.anonymize(command(refused)), ErrorCode.REQUEST_NOT_ELIGIBLE, 409);
        assertNothingChanged(received);
        assertNothingChanged(refused);
    }

    @Test
    void anEmailDeletionWithoutALinkedAccountHasNoAccountToAnonymize() {
        DataSubjectRequest request = store.add(DataSubjectRequest.fromEmail(
                        UUID.randomUUID(),
                        Optional.empty(),
                        DataRequestType.DELETION,
                        "x",
                        "titular@example.org",
                        clock.instant(),
                        RESPONSE_TIME)
                .inProgress(false));

        assertRefused(() -> service.anonymize(command(request)), ErrorCode.NO_LINKED_ACCOUNT, 409);
        assertNothingChanged(request);
    }

    @Test
    void anAdministratorAccountIsNeverAnonymized() {
        accounts.add(person, tenant, Role.ADMIN);
        DataSubjectRequest request = deletionInProgress();

        assertRefused(() -> service.anonymize(command(request)), ErrorCode.ADMIN_ACCOUNT_NOT_ANONYMIZABLE, 409);
        assertNothingChanged(request);
    }

    @Test
    void anEmailDeletionWithoutAConfirmedIdentityIsRefused() {
        accounts.add(person, tenant, Role.BETA_USER);
        DataSubjectRequest request = store.add(emailDeletion().inProgress(false));

        assertRefused(() -> service.anonymize(command(request)), ErrorCode.IDENTITY_NOT_VERIFIED, 409);
        assertNothingChanged(request);
    }

    @Test
    void theRefusalsComeInTheOrderOfTheDesign() {
        DataSubjectRequest received = store.add(DataSubjectRequest.fromEmail(
                UUID.randomUUID(),
                Optional.empty(),
                DataRequestType.DELETION,
                "x",
                "titular@example.org",
                clock.instant(),
                RESPONSE_TIME));

        // Not IN_PROGRESS AND without an account: the state is checked first.
        assertRefused(() -> service.anonymize(command(received)), ErrorCode.REQUEST_NOT_ELIGIBLE, 409);
        // A wrong confirmation is refused before even reading the request.
        assertRefused(() -> service.anonymize(command(received, "nao")), ErrorCode.CONFIRMATION_REQUIRED, 422);
        // An unconfirmed EMAIL request of an ADMIN account: the role is checked before the identity.
        accounts.add(person, tenant, Role.ADMIN);
        DataSubjectRequest adminEmail = store.add(emailDeletion().inProgress(false));
        assertRefused(() -> service.anonymize(command(adminEmail)), ErrorCode.ADMIN_ACCOUNT_NOT_ANONYMIZABLE, 409);
        assertThat(calls).doesNotContain("J1", "J2", "J3", "J6", "CONCLUDED");
        assertThat(outcomes.failures).isEmpty();
    }

    // ------------------------------------------------------------------ BR5.2: the transaction

    @Test
    void anAccountDeletionIsAnonymizedInTheOrderBotsFeedbackIdentityAuditAndConcluded() {
        accounts.add(person, tenant, Role.BETA_USER);
        bots.stopped = 3;
        feedback.deleted = 4;
        accounts.sessions = 2;
        DataSubjectRequest request = deletionInProgress();

        AnonymizationResult result = service.anonymize(command(request));

        assertThat(calls).containsExactly("TIMEOUT", "J2", "J3", "J1", "J6", "CONCLUDED");
        assertThat(result.requestId()).isEqualTo(request.requestId());
        assertThat(result.userId()).isEqualTo(person);
        assertThat(result.anonymizedAt()).isEqualTo(clock.instant());
        assertThat(result.botsStopped()).isEqualTo(3);
        assertThat(result.feedbackDeleted()).isEqualTo(4);
        assertThat(result.sessionsRevoked()).isEqualTo(2);
        assertThat(result.alreadyAnonymized()).isFalse();
        DataSubjectRequest done = store.requests.get(request.requestId());
        assertThat(done.status()).isEqualTo(DataRequestStatus.DONE);
        assertThat(done.response()).contains(AnonymizationService.DELETION_RESPONSE);
        assertThat(done.closedAt()).contains(clock.instant());
        assertThat(requestEvents.transitions).containsExactly(DataRequestStatus.IN_PROGRESS + ">DONE:ADMIN");
        assertThat(events.published).containsExactly(result);
        assertThat(outcomes.succeeded).containsExactly(result);
        assertThat(bots.scope).isEqualTo(tenant + "/" + person);
        assertThat(feedback.scope).isEqualTo(tenant + "/" + person);
    }

    @Test
    void anEmailDeletionWithAConfirmedIdentityIsAnonymized() {
        accounts.add(person, tenant, Role.BETA_USER);
        DataSubjectRequest request = store.add(emailDeletion().inProgress(true));

        AnonymizationResult result = service.anonymize(command(request));

        assertThat(result.alreadyAnonymized()).isFalse();
        assertThat(store.requests.get(request.requestId()).status()).isEqualTo(DataRequestStatus.DONE);
        assertThat(calls).containsSubsequence("J2", "J3", "J1", "J6", "CONCLUDED");
    }

    // ------------------------------------------------------------------ BR5.5: the repetition

    @Test
    void aRepetitionOnAnAnonymizedAccountAnswersTheSameResultWithoutAWriteOrAnEvent() {
        accounts.add(person, tenant, Role.BETA_USER);
        DataSubjectRequest request = deletionInProgress();
        AnonymizationResult first = service.anonymize(command(request));
        calls.clear();
        clock.advance(Duration.ofMinutes(5));

        AnonymizationResult again = service.anonymize(command(request));

        assertThat(again.alreadyAnonymized()).isTrue();
        assertThat(again.requestId()).isEqualTo(first.requestId());
        assertThat(again.userId()).isEqualTo(first.userId());
        assertThat(again.anonymizedAt()).isEqualTo(first.anonymizedAt());
        assertThat(again.botsStopped() + again.sessionsRevoked() + again.feedbackDeleted())
                .isZero();
        assertThat(calls).containsExactly("TIMEOUT");
        assertThat(events.published).hasSize(1);
        assertThat(requestEvents.transitions).hasSize(1);
        assertThat(outcomes.repeated).isEqualTo(1);
    }

    @Test
    void anotherDeletionInProgressOfAnAnonymizedAccountIsConcludedWithoutTouchingTheAccount() {
        accounts.add(person, tenant, Role.BETA_USER);
        accounts.anonymize(person, clock.instant());
        DataSubjectRequest request = deletionInProgress();

        AnonymizationResult result = service.anonymize(command(request));

        assertThat(result.alreadyAnonymized()).isTrue();
        assertThat(store.requests.get(request.requestId()).status()).isEqualTo(DataRequestStatus.DONE);
        assertThat(calls).containsExactly("TIMEOUT", "CONCLUDED");
        assertThat(events.published).isEmpty();
        assertThat(requestEvents.transitions).containsExactly("IN_PROGRESS>DONE:ADMIN");
    }

    @Test
    void aDeletionNotInProgressOfAnAnonymizedAccountIsStillNotEligible() {
        accounts.add(person, tenant, Role.BETA_USER);
        accounts.anonymize(person, clock.instant());
        DataSubjectRequest received = store.add(DataSubjectRequest.fromAccount(
                UUID.randomUUID(), tenant, person, DataRequestType.DELETION, "x", clock.instant(), RESPONSE_TIME));
        DataSubjectRequest refused = store.add(DataSubjectRequest.fromAccount(
                        UUID.randomUUID(),
                        tenant,
                        person,
                        DataRequestType.DELETION,
                        "x",
                        clock.instant(),
                        RESPONSE_TIME)
                .inProgress(false)
                .refused("Motivo sintético", clock.instant()));

        assertRefused(() -> service.anonymize(command(received)), ErrorCode.REQUEST_NOT_ELIGIBLE, 409);
        assertRefused(() -> service.anonymize(command(refused)), ErrorCode.REQUEST_NOT_ELIGIBLE, 409);
        assertThat(store.requests.get(received.requestId())).isEqualTo(received);
        assertThat(store.requests.get(refused.requestId())).isEqualTo(refused);
        assertThat(calls).doesNotContain("J1", "J2", "J3", "J6", "CONCLUDED");
        assertThat(events.published).isEmpty();
        assertThat(requestEvents.transitions).isEmpty();
    }

    @Test
    void theSameKeyReplaysTheStoredResultWithoutRunningAgain() {
        accounts.add(person, tenant, Role.BETA_USER);
        bots.stopped = 2;
        DataSubjectRequest request = deletionInProgress();
        IdempotencyKey key = key();

        AnonymizationResult first = service.anonymize(command(request, AnonymizationService.CONFIRMATION, key));
        calls.clear();
        AnonymizationResult replay = service.anonymize(command(request, AnonymizationService.CONFIRMATION, key));

        assertThat(replay).isEqualTo(first);
        assertThat(calls).isEmpty();
    }

    // ------------------------------------------------------------------ NFR5.2, NFR5.4, NFR8.4: the failures

    @Test
    void aFailureInTheMiddleStopsTheSequenceAndIsObservedAsRolledBack() {
        accounts.add(person, tenant, Role.BETA_USER);
        feedback.failure = new IllegalStateException("simulated failure of J3");
        DataSubjectRequest request = deletionInProgress();

        assertThatThrownBy(() -> service.anonymize(command(request)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated failure of J3");

        assertThat(calls).containsExactly("TIMEOUT", "J2", "J3");
        assertThat(outcomes.failures).containsExactly(AnonymizationOutcomes.FailureReason.ROLLED_BACK);
        assertThat(events.published).isEmpty();
        assertThat(outcomes.succeeded).isEmpty();
    }

    @Test
    void aLockTimeoutIs503LockTimeoutWithRetryAfterAndIsObserved() {
        accounts.add(person, tenant, Role.BETA_USER);
        bots.failure = new CannotAcquireLockException("lock_timeout");
        DataSubjectRequest request = deletionInProgress();

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.anonymize(command(request)));

        assertThat(thrown).isInstanceOf(ApiException.class);
        ApiException problem = (ApiException) thrown;
        assertThat(problem.code()).isEqualTo(ErrorCode.LOCK_TIMEOUT);
        assertThat(problem.code().status()).isEqualTo(503);
        assertThat(problem.headers()).containsKey("Retry-After");
        assertThat(outcomes.failures).containsExactly(AnonymizationOutcomes.FailureReason.LOCK_TIMEOUT);
        assertThat(store.requests.get(request.requestId()).status()).isEqualTo(DataRequestStatus.IN_PROGRESS);
    }

    @Test
    void aLockTimeoutReportedOnlyByItsSqlStateIsRecognisedToo() {
        accounts.add(person, tenant, Role.BETA_USER);
        bots.failure = new org.springframework.jdbc.UncategorizedSQLException(
                "select", "select", new java.sql.SQLException("canceling statement due to lock timeout", "55P03"));
        DataSubjectRequest request = deletionInProgress();

        assertRefused(() -> service.anonymize(command(request)), ErrorCode.LOCK_TIMEOUT, 503);
        assertThat(outcomes.failures).containsExactly(AnonymizationOutcomes.FailureReason.LOCK_TIMEOUT);
    }

    @Test
    void theResultIsStoredWithIdentifiersInstantAndCountsOnly() {
        AnonymizationResult result =
                new AnonymizationResult(UUID.randomUUID(), UUID.randomUUID(), clock.instant(), 1, 2, 3, false);

        String stored = result.toStored();

        assertThat(AnonymizationResult.fromStored(stored)).isEqualTo(result);
        assertThat(AnonymizationResult.fromStored(stored.replace("\":", "\": ").replace(",", ", ")))
                .isEqualTo(result);
        assertThatThrownBy(() -> AnonymizationResult.fromStored("{\"requestId\":\"x\"}"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                        new AnonymizationResult(UUID.randomUUID(), UUID.randomUUID(), clock.instant(), -1, 0, 0, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ helpers

    private DataSubjectRequest deletionInProgress() {
        return store.add(DataSubjectRequest.fromAccount(
                        UUID.randomUUID(),
                        tenant,
                        person,
                        DataRequestType.DELETION,
                        "x",
                        clock.instant(),
                        RESPONSE_TIME)
                .inProgress(false));
    }

    private DataSubjectRequest emailDeletion() {
        return DataSubjectRequest.fromEmail(
                UUID.randomUUID(),
                Optional.of(new DataSubjectRequest.LinkedAccount(tenant, person)),
                DataRequestType.DELETION,
                "x",
                "titular@example.org",
                clock.instant(),
                RESPONSE_TIME);
    }

    private AnonymizationService.Anonymize command(DataSubjectRequest request) {
        return command(request, AnonymizationService.CONFIRMATION);
    }

    private AnonymizationService.Anonymize command(DataSubjectRequest request, String confirmation) {
        return command(request, confirmation, key());
    }

    private AnonymizationService.Anonymize command(
            DataSubjectRequest request, String confirmation, IdempotencyKey key) {
        return new AnonymizationService.Anonymize(admin, request.requestId(), confirmation, key);
    }

    private static IdempotencyKey key() {
        return IdempotencyKey.parse(UUID.randomUUID().toString());
    }

    private void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code, int status) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class).satisfies(thrown -> {
            assertThat(((ApiException) thrown).code()).isEqualTo(code);
            assertThat(((ApiException) thrown).code().status()).isEqualTo(status);
        });
    }

    private void assertNothingChanged(DataSubjectRequest request) {
        assertThat(store.requests.get(request.requestId())).isEqualTo(request);
        assertThat(calls).doesNotContain("J1", "J2", "J3", "J6", "CONCLUDED");
        assertThat(events.published).isEmpty();
        assertThat(accounts.anonymized).doesNotContain(person);
    }

    // ------------------------------------------------------------------ fakes

    private final class Store implements DataRequestStore {
        private final Map<UUID, DataSubjectRequest> requests = new HashMap<>();

        DataSubjectRequest add(DataSubjectRequest request) {
            requests.put(request.requestId(), request);
            return request;
        }

        @Override
        public void insert(DataSubjectRequest request) {
            add(request);
        }

        @Override
        public boolean existsOpen(UUID tenantId, UUID userId, DataRequestType type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<DataSubjectRequest> findOwned(UUID tenantId, UUID userId, UUID requestId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DataSubjectRequest> listOwned(UUID tenantId, UUID userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<DataSubjectRequest> findById(UUID requestId) {
            return Optional.ofNullable(requests.get(requestId));
        }

        @Override
        public Optional<DataSubjectRequest> lockById(UUID requestId) {
            return Optional.ofNullable(requests.get(requestId));
        }

        @Override
        public void update(DataSubjectRequest request) {
            requests.put(request.requestId(), request);
        }

        @Override
        public List<DataSubjectRequest> page(Optional<DataRequestStatus> status, Optional<PageKey> after, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DataSubjectRequest> refuseExpiredVerifications(Instant now, String reason, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DataSubjectRequest> markOverdue(Instant now, int limit) {
            throw new UnsupportedOperationException();
        }
    }

    private final class Accounts implements AccountAnonymizationPort {
        private final Map<UUID, AnonymizationTarget> targets = new HashMap<>();
        private final Set<UUID> anonymized = new HashSet<>();
        private int sessions;

        void add(UUID userId, UUID tenantId, Role role) {
            targets.put(userId, new AnonymizationTarget(userId, tenantId, role, false, Optional.empty()));
        }

        void anonymize(UUID userId, Instant at) {
            AnonymizationTarget current = targets.get(userId);
            targets.put(
                    userId, new AnonymizationTarget(userId, current.tenantId(), current.role(), true, Optional.of(at)));
            anonymized.add(userId);
        }

        @Override
        public Optional<AnonymizationTarget> target(UUID userId) {
            return Optional.ofNullable(targets.get(userId));
        }

        @Override
        public AccountAnonymization anonymizeAccount(UUID tenantId, UUID userId, Instant now) {
            calls.add("J1");
            AnonymizationTarget current = targets.get(userId);
            if (current.anonymized()) {
                return new AccountAnonymization(userId, current.anonymizedAt().orElseThrow(), 0, true);
            }
            anonymize(userId, now);
            return new AccountAnonymization(userId, now, sessions, false);
        }

        @Override
        public Set<UUID> anonymizedAmong(Collection<UUID> userIds) {
            Set<UUID> found = new HashSet<>(userIds);
            found.retainAll(anonymized);
            return found;
        }
    }

    private final class Bots implements BotOwnerDataPort {
        private int stopped;
        private RuntimeException failure;
        private String scope;

        @Override
        public long countOwnerBots(UUID tenantId, UUID ownerUserId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int anonymizeOwnerBots(UUID tenantId, UUID ownerUserId) {
            calls.add("J2");
            scope = tenantId + "/" + ownerUserId;
            if (failure != null) {
                throw failure;
            }
            return stopped;
        }
    }

    private final class Feedback implements FeedbackOwnerDataPort {
        private int deleted;
        private RuntimeException failure;
        private String scope;

        @Override
        public long countByUser(UUID tenantId, UUID userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteByUser(UUID tenantId, UUID userId) {
            calls.add("J3");
            scope = tenantId + "/" + userId;
            if (failure != null) {
                throw failure;
            }
            return deleted;
        }
    }

    private final class Events implements AnonymizationEvents {
        private final List<AnonymizationResult> published = new ArrayList<>();

        @Override
        public void accountAnonymized(DataSubjectRequest request, AnonymizationResult result, Principal by) {
            calls.add("J6");
            assertThat(by).isEqualTo(admin);
            published.add(result);
        }
    }

    private final class RequestEvents implements DataRequestEvents {
        private final List<String> transitions = new ArrayList<>();

        @Override
        public void created(DataSubjectRequest request, Actor actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void transitioned(DataSubjectRequest request, DataRequestStatus from, Actor actor) {
            calls.add("CONCLUDED");
            transitions.add(from + ">" + request.status() + ":" + actor.kind());
        }

        @Override
        public void verificationStarted(DataSubjectRequest request, Actor actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void verified(DataSubjectRequest request, Actor actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void overdue(DataSubjectRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class Outcomes implements AnonymizationOutcomes {
        private final List<AnonymizationResult> succeeded = new ArrayList<>();
        private final List<FailureReason> failures = new ArrayList<>();
        private int repeated;
        private int refused;

        @Override
        public Attempt start(UUID requestId) {
            return new Attempt() {
                @Override
                public void succeeded(AnonymizationResult result) {
                    succeeded.add(result);
                }

                @Override
                public void repeated() {
                    repeated++;
                }

                @Override
                public void refused() {
                    refused++;
                }

                @Override
                public void failed(FailureReason reason, Throwable cause) {
                    failures.add(reason);
                }
            };
        }
    }
}
