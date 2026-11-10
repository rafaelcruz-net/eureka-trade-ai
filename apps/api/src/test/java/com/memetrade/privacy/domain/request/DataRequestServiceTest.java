package com.memetrade.privacy.domain.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.PersonDataPort.AccountState;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.FieldViolation;
import com.memetrade.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Passo 3 of u4-data-requests, written BEFORE the code (team.md Testing Posture: personal data logic first). The rules
 * of the requests, from rules.md:
 *
 * <ul>
 *   <li>BR4.1: four types, text of 1 to 1000 characters, RECEIVED due 15 days later by the clock; 400 stores nothing;
 *   <li>BR4.2: one open request per person and type, from any origin, also in the e-mail registration (409);
 *   <li>BR4.4: a person reads only her own requests (404 otherwise); the answer only after DONE or REFUSED;
 *   <li>BR4.5: only the allowed transitions; RECEIVED to IN_PROGRESS refused for an EMAIL ACCESS or DELETION linked to an
 *       active account; REFUSED without a reason 422; a closed request 409;
 *   <li>BR4.9: releasing the summary of an EMAIL request needs a confirmed identity (409);
 *   <li>BR4.10: an EMAIL request with an ANONYMIZED account is accepted without verification.
 * </ul>
 */
@Tag("u4-data-requests")
class DataRequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T09:30:00Z");
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-00000000c001");
    private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-0000-0000-00000000c002");
    private static final String DETAILS = "Quero saber quais dados meus vocês guardam.";

    private final MutableClock clock = new MutableClock(NOW);
    private final RequestFakes.Store store = new RequestFakes.Store();
    private final RequestFakes.Idempotency idempotency = new RequestFakes.Idempotency();
    private final RequestFakes.Events events = new RequestFakes.Events();
    private final RequestFakes.RateLimit rateLimit = new RequestFakes.RateLimit();
    private final RequestFakes.People people = new RequestFakes.People();
    private final RequestFakes.Summaries summaries = new RequestFakes.Summaries();
    private final DataRequestService service = new DataRequestService(
            store, store, idempotency, rateLimit, events, people, summaries, clock, Duration.ofDays(15));

    private final Principal person = new Principal(TENANT, people.account(TENANT, AccountState.ACTIVE), Role.BETA_USER);
    private final Principal admin =
            new Principal(UUID.fromString("00000000-0000-0000-0000-00000000ad01"), UUID.randomUUID(), Role.ADMIN);

    private static IdempotencyKey key() {
        return new IdempotencyKey(UUID.randomUUID());
    }

    private DataSubjectRequest create(Principal who, String type) {
        return service.create(new DataRequestService.NewRequest(who, type, DETAILS, key()))
                .request();
    }

    private DataSubjectRequest emailRequest(String type, Optional<UUID> userId) {
        return service.registerEmail(new DataRequestService.EmailRequest(
                admin,
                type,
                DETAILS,
                "titular@example.org",
                NOW.minus(Duration.ofDays(1)).toString(),
                userId.map(UUID::toString).orElse(null),
                key()));
    }

    private DataSubjectRequest transition(UUID requestId, String to, String response, String reason, boolean release) {
        return service.transition(
                new DataRequestService.Transition(admin, requestId, to, response, reason, release, key()));
    }

    private static List<String> fields(ApiException refused) {
        return refused.fields().stream().map(FieldViolation::field).toList();
    }

    // ---------------------------------------------------------------- BR4.1

    @ParameterizedTest
    @CsvSource({"ACCESS", "CORRECTION", "DELETION", "OTHER"})
    void aPersonCreatesEachOfTheFourTypesReceivedAndDueFifteenDaysLater(String type) {
        DataSubjectRequest created = create(person, type);

        assertThat(created.type()).isEqualTo(DataRequestType.valueOf(type));
        assertThat(created.origin()).isEqualTo(DataRequestOrigin.ACCOUNT);
        assertThat(created.status()).isEqualTo(DataRequestStatus.RECEIVED);
        assertThat(created.receivedAt()).isEqualTo(NOW);
        assertThat(created.dueAt()).isEqualTo(Instant.parse("2026-11-25T09:30:00Z"));
        assertThat(created.tenantId()).contains(TENANT);
        assertThat(created.userId()).contains(person.userId());
        assertThat(created.contact()).isEmpty();
        assertThat(store.requests).containsKey(created.requestId());
        assertThat(events.kinds()).containsExactly("CREATED");
        assertThat(events.published.getFirst().actor()).isEqualTo("PERSON");
    }

    @Test
    void textOfExactlyOneThousandCharactersIsAccepted() {
        String limit = "a".repeat(1000);

        DataSubjectRequest created = service.create(new DataRequestService.NewRequest(person, "OTHER", limit, key()))
                .request();

        assertThat(created.details()).hasSize(1000);
    }

    @Test
    void anInvalidTypeAnEmptyOrALongTextIs400AndNothingIsStored() {
        ApiException refused = (ApiException) org.assertj.core.api.Assertions.catchThrowable(
                () -> service.create(new DataRequestService.NewRequest(person, "EVERYTHING", "a".repeat(1001), key())));

        assertThat(refused.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(fields(refused)).containsExactlyInAnyOrder("type", "details");
        assertThat(refused.getMessage()).doesNotContain("aaaa");
        assertThat(refused.fields().toString()).doesNotContain("aaaa").doesNotContain("EVERYTHING");
        assertThatThrownBy(() -> service.create(new DataRequestService.NewRequest(person, "ACCESS", "   ", key())))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(fields((ApiException) error)).containsExactly("details"));
        assertThat(store.requests).isEmpty();
        assertThat(events.published).isEmpty();
        assertThat(idempotency.executions).isZero();
    }

    @Test
    void theSameKeyAndBodyReplayTheSameRequestWithOneEvent() {
        IdempotencyKey sameKey = key();
        DataRequestService.Created first =
                service.create(new DataRequestService.NewRequest(person, "ACCESS", DETAILS, sameKey));

        DataRequestService.Created again =
                service.create(new DataRequestService.NewRequest(person, "ACCESS", DETAILS, sameKey));

        assertThat(again.replayed()).isTrue();
        assertThat(again.request().requestId()).isEqualTo(first.request().requestId());
        assertThat(store.requests).hasSize(1);
        assertThat(events.kinds()).containsExactly("CREATED");
    }

    @Test
    void theLimitPerHourIs429WithRetryAfterAndStoresNothing() {
        rateLimit.refusal = Optional.of(Duration.ofMinutes(20));

        assertThatThrownBy(() -> create(person, "ACCESS"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException refused = (ApiException) error;
                    assertThat(refused.code()).isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(refused.headers()).containsEntry("Retry-After", "1200");
                });
        assertThat(store.requests).isEmpty();
        assertThat(events.published).isEmpty();
    }

    // ---------------------------------------------------------------- BR4.2

    @Test
    void aSecondOpenRequestOfTheSameTypeIs409AndAnotherTypeIsAccepted() {
        create(person, "ACCESS");

        assertThatThrownBy(() -> create(person, "ACCESS"))
                .isInstanceOf(ApiException.class)
                .satisfies(error ->
                        assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.DATA_REQUEST_ALREADY_OPEN));
        DataSubjectRequest correction = create(person, "CORRECTION");

        assertThat(correction.status()).isEqualTo(DataRequestStatus.RECEIVED);
        assertThat(store.requests).hasSize(2);
    }

    @Test
    void aClosedRequestNoLongerBlocksANewOneOfTheSameType() {
        DataSubjectRequest first = create(person, "OTHER");
        transition(first.requestId(), "IN_PROGRESS", null, null, false);
        transition(first.requestId(), "DONE", "Respondido.", null, false);

        DataSubjectRequest second = create(person, "OTHER");

        assertThat(second.requestId()).isNotEqualTo(first.requestId());
    }

    @Test
    void aRaceOnTheUniqueIndexIsAlso409() {
        DataRequestStore racing = new RequestFakes.Store() {
            @Override
            public boolean existsOpen(UUID tenantId, UUID userId, DataRequestType type) {
                return false;
            }

            @Override
            public void insert(DataSubjectRequest request) {
                throw new OpenRequestConflictException();
            }
        };
        DataRequestService raced = new DataRequestService(
                racing, store, idempotency, rateLimit, events, people, summaries, clock, Duration.ofDays(15));

        assertThatThrownBy(() -> raced.create(new DataRequestService.NewRequest(person, "ACCESS", DETAILS, key())))
                .isInstanceOf(ApiException.class)
                .satisfies(error ->
                        assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.DATA_REQUEST_ALREADY_OPEN));
        assertThat(events.published).isEmpty();
    }

    @Test
    void anEmailRequestLinkedToAnAccountWithAnOpenRequestOfTheSameTypeIs409() {
        create(person, "DELETION");

        assertThatThrownBy(() -> emailRequest("DELETION", Optional.of(person.userId())))
                .isInstanceOf(ApiException.class)
                .satisfies(error ->
                        assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.DATA_REQUEST_ALREADY_OPEN));
    }

    // ---------------------------------------------------------------- BR4.4

    @Test
    void aPersonListsOnlyHerOwnRequestsNewestFirst() {
        DataSubjectRequest older = create(person, "ACCESS");
        clock.advance(Duration.ofMinutes(5));
        DataSubjectRequest newer = create(person, "OTHER");
        Principal stranger = new Principal(TENANT, people.account(TENANT, AccountState.ACTIVE), Role.BETA_USER);
        create(stranger, "ACCESS");

        assertThat(service.listMine(person))
                .extracting(DataSubjectRequest::requestId)
                .containsExactly(newer.requestId(), older.requestId());
    }

    @Test
    void theRequestOfAnotherPersonOrTenantIs404() {
        DataSubjectRequest mine = create(person, "ACCESS");
        Principal samePersonOtherTenant = new Principal(OTHER_TENANT, person.userId(), Role.BETA_USER);
        Principal stranger = new Principal(TENANT, UUID.randomUUID(), Role.BETA_USER);

        for (Principal other : List.of(samePersonOtherTenant, stranger)) {
            assertThatThrownBy(() -> service.getMine(other, mine.requestId()))
                    .isInstanceOf(ApiException.class)
                    .satisfies(
                            error -> assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.NOT_FOUND));
        }
        assertThatThrownBy(() -> service.getMine(person, UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void theResponseAndTheSummaryAppearToThePersonOnlyOnceTheRequestIsDone() {
        DataSubjectRequest access = create(person, "ACCESS");
        transition(access.requestId(), "IN_PROGRESS", null, null, false);

        assertThat(service.getMine(person, access.requestId()).forPerson().response())
                .isEmpty();
        transition(access.requestId(), "DONE", "Segue o resumo.", null, true);

        DataSubjectRequest seen = service.getMine(person, access.requestId()).forPerson();
        assertThat(seen.response()).contains("Segue o resumo.");
        assertThat(seen.accessSummary()).isPresent();
        assertThat(seen.accessSummary().get().botCount()).isEqualTo(2);
    }

    @Test
    void aRefusedRequestShowsTheReasonToThePersonAndAnOpenOneShowsNothing() {
        DataSubjectRequest request = create(person, "OTHER");
        DataSubjectRequest open = service.getMine(person, request.requestId()).forPerson();
        transition(request.requestId(), "IN_PROGRESS", null, null, false);
        transition(request.requestId(), "REFUSED", null, "Pedido sem relação com a conta.", false);

        DataSubjectRequest seen = service.getMine(person, request.requestId()).forPerson();

        assertThat(open.response()).isEmpty();
        assertThat(open.refusalReason()).isEmpty();
        assertThat(open.accessSummary()).isEmpty();
        assertThat(seen.refusalReason()).contains("Pedido sem relação com a conta.");
    }

    // ---------------------------------------------------------------- BR4.5

    @Test
    void theAllowedPathReceivedInProgressDone() {
        DataSubjectRequest request = create(person, "CORRECTION");

        DataSubjectRequest started = transition(request.requestId(), "IN_PROGRESS", null, null, false);
        clock.advance(Duration.ofHours(2));
        DataSubjectRequest done = transition(request.requestId(), "DONE", "Corrigido.", null, false);

        assertThat(started.status()).isEqualTo(DataRequestStatus.IN_PROGRESS);
        assertThat(done.status()).isEqualTo(DataRequestStatus.DONE);
        assertThat(done.closedAt()).contains(NOW.plus(Duration.ofHours(2)));
        assertThat(done.response()).contains("Corrigido.");
        assertThat(events.published)
                .extracting(RequestFakes.Event::kind, RequestFakes.Event::from, RequestFakes.Event::to)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("CREATED", Optional.empty(), DataRequestStatus.RECEIVED),
                        org.assertj.core.groups.Tuple.tuple(
                                "TRANSITIONED", Optional.of(DataRequestStatus.RECEIVED), DataRequestStatus.IN_PROGRESS),
                        org.assertj.core.groups.Tuple.tuple(
                                "TRANSITIONED", Optional.of(DataRequestStatus.IN_PROGRESS), DataRequestStatus.DONE));
        assertThat(events.published.get(1).actor()).isEqualTo("ADMIN");
    }

    @Test
    void inProgressToRefusedNeedsAReasonOtherwise422AndNothingChanges() {
        DataSubjectRequest request = create(person, "OTHER");
        transition(request.requestId(), "IN_PROGRESS", null, null, false);

        for (String missing : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> transition(request.requestId(), "REFUSED", null, missing, false))
                    .isInstanceOf(ApiException.class)
                    .satisfies(error -> {
                        ApiException refused = (ApiException) error;
                        assertThat(refused.code()).isEqualTo(ErrorCode.UNPROCESSABLE_FIELDS);
                        assertThat(fields(refused)).containsExactly("refusalReason");
                    });
        }
        assertThat(store.requests.get(request.requestId()).status()).isEqualTo(DataRequestStatus.IN_PROGRESS);

        DataSubjectRequest refused = transition(request.requestId(), "REFUSED", null, "Fora do escopo.", false);
        assertThat(refused.status()).isEqualTo(DataRequestStatus.REFUSED);
        assertThat(refused.refusalReason()).contains("Fora do escopo.");
        assertThat(refused.closedAt()).contains(NOW);
    }

    @ParameterizedTest
    @CsvSource({"DONE", "REFUSED"})
    void receivedCannotJumpToDoneOrRefused(String to) {
        DataSubjectRequest request = create(person, "OTHER");

        assertThatThrownBy(() -> transition(request.requestId(), to, "x", "motivo", false))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.INVALID_TRANSITION));
        assertThat(store.requests.get(request.requestId()).status()).isEqualTo(DataRequestStatus.RECEIVED);
    }

    @Test
    void inProgressCannotGoBackToInProgress() {
        DataSubjectRequest request = create(person, "OTHER");
        transition(request.requestId(), "IN_PROGRESS", null, null, false);

        assertThatThrownBy(() -> transition(request.requestId(), "IN_PROGRESS", null, null, false))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.INVALID_TRANSITION));
    }

    @ParameterizedTest
    @CsvSource({"IN_PROGRESS", "DONE", "REFUSED"})
    void aClosedRequestNeverChangesAnd409Closed(String to) {
        DataSubjectRequest request = create(person, "OTHER");
        transition(request.requestId(), "IN_PROGRESS", null, null, false);
        transition(request.requestId(), "DONE", "Feito.", null, false);
        int before = events.published.size();

        assertThatThrownBy(() -> transition(request.requestId(), to, "outra", "motivo", false))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.DATA_REQUEST_CLOSED));
        assertThat(store.requests.get(request.requestId()).response()).contains("Feito.");
        assertThat(events.published).hasSize(before);
    }

    @Test
    void anUnknownTargetStateIs400OnTo() {
        DataSubjectRequest request = create(person, "OTHER");

        assertThatThrownBy(() -> transition(request.requestId(), "AWAITING_VERIFICATION", null, null, false))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(fields((ApiException) error)).containsExactly("to");
                });
    }

    @Test
    void aResponseOrAReasonAboveItsLimitIs400() {
        DataSubjectRequest request = create(person, "OTHER");
        transition(request.requestId(), "IN_PROGRESS", null, null, false);

        assertThatThrownBy(() -> transition(request.requestId(), "DONE", "r".repeat(4001), null, false))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(fields((ApiException) error)).containsExactly("response"));
        assertThatThrownBy(() -> transition(request.requestId(), "REFUSED", null, "m".repeat(501), false))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(fields((ApiException) error)).containsExactly("refusalReason"));
    }

    @Test
    void anUnknownRequestIs404ForTheAdministrator() {
        assertThatThrownBy(() -> transition(UUID.randomUUID(), "IN_PROGRESS", null, null, false))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @ParameterizedTest
    @CsvSource({"ACCESS", "DELETION"})
    void anEmailAccessOrDeletionLinkedToAnActiveAccountCannotGoStraightToInProgress(String type) {
        DataSubjectRequest email = emailRequest(type, Optional.of(person.userId()));

        assertThatThrownBy(() -> transition(email.requestId(), "IN_PROGRESS", null, null, false))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException refused = (ApiException) error;
                    assertThat(refused.code()).isEqualTo(ErrorCode.INVALID_TRANSITION);
                    assertThat(refused.getMessage()).containsIgnoringCase("verification");
                });
        assertThat(store.requests.get(email.requestId()).status()).isEqualTo(DataRequestStatus.RECEIVED);
    }

    @ParameterizedTest
    @CsvSource({"CORRECTION", "OTHER"})
    void anEmailCorrectionOrOtherGoesStraightToInProgress(String type) {
        DataSubjectRequest email = emailRequest(type, Optional.of(person.userId()));

        assertThat(transition(email.requestId(), "IN_PROGRESS", null, null, false)
                        .status())
                .isEqualTo(DataRequestStatus.IN_PROGRESS);
    }

    @Test
    void anEmailAccessWithoutAnAccountGoesStraightToInProgress() {
        DataSubjectRequest email = emailRequest("ACCESS", Optional.empty());

        assertThat(transition(email.requestId(), "IN_PROGRESS", null, null, false)
                        .status())
                .isEqualTo(DataRequestStatus.IN_PROGRESS);
    }

    @Test
    void awaitingVerificationNeverGoesToInProgressByATransitionButMayBeRefusedExpiringTheVerification() {
        DataSubjectRequest email = emailRequest("ACCESS", Optional.of(person.userId()));
        DataSubjectRequest awaiting = email.awaitingVerification();
        store.update(awaiting);
        store.insert(new IdentityVerification(
                email.requestId(),
                "msg",
                NOW,
                NOW.plus(Duration.ofHours(24)),
                VerificationState.PENDING,
                Optional.empty()));

        assertThatThrownBy(() -> transition(email.requestId(), "IN_PROGRESS", null, null, false))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.INVALID_TRANSITION));
        DataSubjectRequest refused = transition(email.requestId(), "REFUSED", null, "Carteira perdida.", false);

        assertThat(refused.status()).isEqualTo(DataRequestStatus.REFUSED);
        assertThat(store.verifications.get(email.requestId()).state()).isEqualTo(VerificationState.EXPIRED);
    }

    // ---------------------------------------------------------------- BR4.8, BR4.9

    @Test
    void releasingTheSummaryOfAnEmailAccessWithoutAConfirmedIdentityIs409() {
        DataSubjectRequest email = emailRequest("ACCESS", Optional.empty());
        transition(email.requestId(), "IN_PROGRESS", null, null, false);

        assertThatThrownBy(() -> transition(email.requestId(), "DONE", "Resumo.", null, true))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        error -> assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.IDENTITY_NOT_VERIFIED));
        assertThat(store.requests.get(email.requestId()).status()).isEqualTo(DataRequestStatus.IN_PROGRESS);
        assertThat(summaries.assembled).isZero();
    }

    @Test
    void anAccountAccessReleasesTheSummaryAsASnapshotWithoutVerification() {
        DataSubjectRequest access = create(person, "ACCESS");
        transition(access.requestId(), "IN_PROGRESS", null, null, false);

        DataSubjectRequest done = transition(access.requestId(), "DONE", "Segue.", null, true);

        assertThat(done.accessSummary()).isPresent();
        assertThat(done.identityVerified()).isFalse();
        assertThat(summaries.assembled).isEqualTo(1);
    }

    @Test
    void doneWithoutReleaseKeepsNoSummary() {
        DataSubjectRequest access = create(person, "ACCESS");
        transition(access.requestId(), "IN_PROGRESS", null, null, false);

        assertThat(transition(access.requestId(), "DONE", "Sem resumo.", null, false)
                        .accessSummary())
                .isEmpty();
    }

    @Test
    void onlyAnAccessRequestReleasesASummary() {
        DataSubjectRequest other = create(person, "OTHER");
        transition(other.requestId(), "IN_PROGRESS", null, null, false);

        assertThatThrownBy(() -> transition(other.requestId(), "DONE", "x", null, true))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.UNPROCESSABLE_FIELDS);
                    assertThat(fields((ApiException) error)).containsExactly("releaseAccessSummary");
                });
    }

    // ---------------------------------------------------------------- BR4.10

    @Test
    void anEmailRequestIsRegisteredReceivedWithTheContactAndTheTenantOfTheAccount() {
        DataSubjectRequest email = emailRequest("CORRECTION", Optional.of(person.userId()));

        assertThat(email.origin()).isEqualTo(DataRequestOrigin.EMAIL);
        assertThat(email.status()).isEqualTo(DataRequestStatus.RECEIVED);
        assertThat(email.contact()).contains("titular@example.org");
        assertThat(email.tenantId()).contains(TENANT);
        assertThat(email.receivedAt()).isEqualTo(NOW.minus(Duration.ofDays(1)));
        assertThat(email.dueAt()).isEqualTo(NOW.minus(Duration.ofDays(1)).plus(Duration.ofDays(15)));
        assertThat(events.published.getLast().actor()).isEqualTo("ADMIN");
    }

    @Test
    void anEmailRequestWithoutAnAccountHasNoTenant() {
        DataSubjectRequest email = emailRequest("OTHER", Optional.empty());

        assertThat(email.tenantId()).isEmpty();
        assertThat(email.userId()).isEmpty();
    }

    @Test
    void anEmailRequestOfAnAnonymizedAccountIsAcceptedAndNeedsNoVerification() {
        UUID anonymized = people.account(TENANT, AccountState.ANONYMIZED);

        DataSubjectRequest email = emailRequest("ACCESS", Optional.of(anonymized));

        assertThat(email.userId()).contains(anonymized);
        assertThat(transition(email.requestId(), "IN_PROGRESS", null, null, false)
                        .status())
                .isEqualTo(DataRequestStatus.IN_PROGRESS);
    }

    @Test
    void theRegistrationRefusesEachBadFieldNamingItNeverItsValue() {
        ApiException refused = (ApiException) org.assertj.core.api.Assertions.catchThrowable(
                () -> service.registerEmail(new DataRequestService.EmailRequest(
                        admin,
                        "NOPE",
                        "",
                        "not-an-email",
                        NOW.plus(Duration.ofHours(1)).toString(),
                        "not-a-uuid",
                        key())));

        assertThat(refused.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(fields(refused)).containsExactlyInAnyOrder("type", "details", "contact", "receivedAt", "userId");
        assertThat(refused.fields().toString()).doesNotContain("not-an-email");
        assertThat(store.requests).isEmpty();
    }

    @Test
    void anUnknownAccountIs400OnUserId() {
        ApiException refused = (ApiException) org.assertj.core.api.Assertions.catchThrowable(
                () -> emailRequest("OTHER", Optional.of(UUID.randomUUID())));

        assertThat(refused.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(fields(refused)).containsExactly("userId");
    }

    // ---------------------------------------------------------------- BR4.17 (reply-sent)

    @Test
    void theReplyIsMarkedSentOnlyOnAClosedEmailRequest() {
        DataSubjectRequest account = create(person, "OTHER");
        DataSubjectRequest email = emailRequest("OTHER", Optional.empty());

        assertThatThrownBy(() -> service.markReplySent(admin, email.requestId(), key()))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.INVALID_TRANSITION));
        transition(email.requestId(), "IN_PROGRESS", null, null, false);
        transition(email.requestId(), "DONE", "Respondido.", null, false);
        clock.advance(Duration.ofMinutes(3));

        assertThat(service.markReplySent(admin, email.requestId(), key()).replySentAt())
                .contains(NOW.plus(Duration.ofMinutes(3)));
        transition(account.requestId(), "IN_PROGRESS", null, null, false);
        transition(account.requestId(), "DONE", "Ok.", null, false);
        assertThatThrownBy(() -> service.markReplySent(admin, account.requestId(), key()))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.INVALID_TRANSITION));
    }

    @Test
    void noEventEverCarriesTheText() {
        create(person, "ACCESS");

        assertThat(events.published.toString()).doesNotContain(DETAILS);
        assertThat(store.requests.values().iterator().next().toString())
                .doesNotContain(DETAILS)
                .contains("ACCESS");
    }
}
