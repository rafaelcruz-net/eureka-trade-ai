package com.memetrade.privacy.domain.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.PersonDataPort;
import com.memetrade.identity.api.PersonDataPort.AccountState;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Passo 3 of u4-data-requests, written BEFORE the code: the signature verification of an EMAIL request (rules.md BR4.11
 * to BR4.13; AC5.6.2 to AC5.6.8).
 *
 * <ul>
 *   <li>the message starts with the fixed verification line and carries the domain, the request and the validity of 24
 *       hours; the request goes to AWAITING_VERIFICATION; only an EMAIL ACCESS or DELETION linked to an ACTIVE account
 *       in RECEIVED may start one;
 *   <li>a valid signature confirms once (IN_PROGRESS, identityVerified); an invalid one is 422 and the request keeps
 *       waiting; a repeated one is 409 VERIFICATION_ALREADY_USED; an expired one 409 VERIFICATION_EXPIRED;
 *   <li>DATA_REQUEST_CLOSED comes before any verification error; a manual refusal marks the verification EXPIRED;
 *   <li>the signature is never kept nor published.
 * </ul>
 */
@Tag("u4-data-requests")
class IdentityVerificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T09:30:00Z");
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-00000000d001");
    private static final String DOMAIN = "localhost:3000";

    private final MutableClock clock = new MutableClock(NOW);
    private final RequestFakes.Store store = new RequestFakes.Store();
    private final RequestFakes.Idempotency idempotency = new RequestFakes.Idempotency();
    private final RequestFakes.Events events = new RequestFakes.Events();
    private final RequestFakes.People people = new RequestFakes.People();
    private final DataRequestService requests = new DataRequestService(
            store,
            store,
            idempotency,
            new RequestFakes.RateLimit(),
            events,
            people,
            new RequestFakes.Summaries(),
            clock,
            Duration.ofDays(15));
    private final IdentityVerificationService service = new IdentityVerificationService(
            store, store, idempotency, events, people, clock, Duration.ofHours(24), DOMAIN);

    private final UUID account = people.account(TENANT, AccountState.ACTIVE);
    private final Principal admin =
            new Principal(UUID.fromString("00000000-0000-0000-0000-00000000ad02"), UUID.randomUUID(), Role.ADMIN);

    private static IdempotencyKey key() {
        return new IdempotencyKey(UUID.randomUUID());
    }

    private DataSubjectRequest email(String type, Optional<UUID> userId) {
        return requests.registerEmail(new DataRequestService.EmailRequest(
                admin,
                type,
                "Pedido recebido pelo e-mail do encarregado.",
                "titular@example.org",
                NOW.minus(Duration.ofHours(3)).toString(),
                userId.map(UUID::toString).orElse(null),
                key()));
    }

    private IdentityVerification start(UUID requestId) {
        return service.start(admin, requestId, key());
    }

    private DataSubjectRequest confirm(UUID requestId, String signature) {
        return service.confirm(admin, requestId, signature, key());
    }

    private static ErrorCode code(Throwable error) {
        return ((ApiException) error).code();
    }

    @Test
    void theMessageStartsWithTheVerificationLineAndCarriesDomainRequestAndValidity() {
        DataSubjectRequest request = email("ACCESS", Optional.of(account));

        IdentityVerification verification = start(request.requestId());

        String[] lines = verification.message().split("\n");
        assertThat(lines[0]).isEqualTo(PersonDataPort.VERIFICATION_FIRST_LINE);
        assertThat(verification.message())
                .contains(DOMAIN)
                .contains(request.requestId().toString())
                .contains("verificação de pedido de titular")
                .contains("2026-11-11T09:30:00Z");
        assertThat(verification.issuedAt()).isEqualTo(NOW);
        assertThat(verification.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(verification.state()).isEqualTo(VerificationState.PENDING);
        assertThat(store.requests.get(request.requestId()).status()).isEqualTo(DataRequestStatus.AWAITING_VERIFICATION);
        assertThat(events.kinds()).endsWith("VERIFICATION_STARTED");
    }

    @Test
    void eachRequestHasItsOwnMessage() {
        UUID secondAccount = people.account(TENANT, AccountState.ACTIVE);

        String first = start(email("ACCESS", Optional.of(account)).requestId()).message();
        String second =
                start(email("DELETION", Optional.of(secondAccount)).requestId()).message();

        assertThat(first).isNotEqualTo(second);
    }

    @ParameterizedTest
    @CsvSource({"CORRECTION", "OTHER"})
    void aTypeWithoutVerificationCannotStartOne(String type) {
        DataSubjectRequest request = email(type, Optional.of(account));

        assertThatThrownBy(() -> start(request.requestId()))
                .satisfies(error -> assertThat(code(error)).isEqualTo(ErrorCode.INVALID_TRANSITION));
    }

    @Test
    void anAccountRequestOrAnEmailWithoutAccountOrWithAnAnonymizedOneCannotStartOne() {
        DataSubjectRequest accountRequest = requests.create(new DataRequestService.NewRequest(
                        new Principal(TENANT, account, Role.BETA_USER), "ACCESS", "Meus dados.", key()))
                .request();
        DataSubjectRequest withoutAccount = email("ACCESS", Optional.empty());
        DataSubjectRequest anonymized = email("ACCESS", Optional.of(people.account(TENANT, AccountState.ANONYMIZED)));

        for (DataSubjectRequest request : new DataSubjectRequest[] {accountRequest, withoutAccount, anonymized}) {
            assertThatThrownBy(() -> start(request.requestId()))
                    .satisfies(error -> assertThat(code(error)).isEqualTo(ErrorCode.INVALID_TRANSITION));
        }
        assertThat(store.verifications).isEmpty();
    }

    @Test
    void aValidSignatureConfirmsOnceAndTheRequestGoesInProgress() {
        DataSubjectRequest request = email("ACCESS", Optional.of(account));
        IdentityVerification verification = start(request.requestId());
        clock.advance(Duration.ofHours(23));

        DataSubjectRequest confirmed = confirm(request.requestId(), "valid:" + verification.message());

        assertThat(confirmed.status()).isEqualTo(DataRequestStatus.IN_PROGRESS);
        assertThat(confirmed.identityVerified()).isTrue();
        assertThat(store.verifications.get(request.requestId()).state()).isEqualTo(VerificationState.CONFIRMED);
        assertThat(store.verifications.get(request.requestId()).confirmedAt()).contains(NOW.plus(Duration.ofHours(23)));
        assertThat(events.kinds()).endsWith("VERIFIED");
    }

    @Test
    void anInvalidSignatureIs422AndTheRequestKeepsWaiting() {
        DataSubjectRequest request = email("DELETION", Optional.of(account));
        start(request.requestId());

        assertThatThrownBy(() -> confirm(request.requestId(), "signature-of-another-wallet"))
                .satisfies(error -> assertThat(code(error)).isEqualTo(ErrorCode.VERIFICATION_INVALID));

        assertThat(store.requests.get(request.requestId()).status()).isEqualTo(DataRequestStatus.AWAITING_VERIFICATION);
        assertThat(store.verifications.get(request.requestId()).state()).isEqualTo(VerificationState.PENDING);
        assertThat(events.kinds()).doesNotContain("VERIFIED");
    }

    @Test
    void aSecondConfirmationIs409AlreadyUsed() {
        DataSubjectRequest request = email("ACCESS", Optional.of(account));
        IdentityVerification verification = start(request.requestId());
        confirm(request.requestId(), "valid:" + verification.message());

        assertThatThrownBy(() -> confirm(request.requestId(), "valid:" + verification.message()))
                .satisfies(error -> assertThat(code(error)).isEqualTo(ErrorCode.VERIFICATION_ALREADY_USED));
    }

    @Test
    void aConfirmationAfterTwentyFourHoursIs409Expired() {
        DataSubjectRequest request = email("ACCESS", Optional.of(account));
        IdentityVerification verification = start(request.requestId());
        clock.advance(Duration.ofHours(24));

        assertThatThrownBy(() -> confirm(request.requestId(), "valid:" + verification.message()))
                .satisfies(error -> assertThat(code(error)).isEqualTo(ErrorCode.VERIFICATION_EXPIRED));
        assertThat(store.requests.get(request.requestId()).identityVerified()).isFalse();
    }

    @Test
    void aClosedRequestIs409ClosedBeforeAnyVerificationError() {
        DataSubjectRequest request = email("ACCESS", Optional.of(account));
        IdentityVerification verification = start(request.requestId());
        requests.transition(new DataRequestService.Transition(
                admin, request.requestId(), "REFUSED", null, "Carteira perdida.", false, key()));
        clock.advance(Duration.ofHours(30));

        assertThatThrownBy(() -> confirm(request.requestId(), "valid:" + verification.message()))
                .satisfies(error -> assertThat(code(error)).isEqualTo(ErrorCode.DATA_REQUEST_CLOSED));
        assertThatThrownBy(() -> confirm(request.requestId(), "wrong"))
                .satisfies(error -> assertThat(code(error)).isEqualTo(ErrorCode.DATA_REQUEST_CLOSED));
    }

    @Test
    void theManualRefusalOfAWaitingRequestExpiresTheVerification() {
        DataSubjectRequest request = email("DELETION", Optional.of(account));
        start(request.requestId());

        requests.transition(new DataRequestService.Transition(
                admin, request.requestId(), "REFUSED", null, "Identidade não confirmada.", false, key()));

        assertThat(store.verifications.get(request.requestId()).state()).isEqualTo(VerificationState.EXPIRED);
        assertThat(store.requests.get(request.requestId()).status()).isEqualTo(DataRequestStatus.REFUSED);
    }

    @Test
    void aSecondStartOnAWaitingRequestIsAnInvalidTransition() {
        DataSubjectRequest request = email("ACCESS", Optional.of(account));
        start(request.requestId());

        assertThatThrownBy(() -> start(request.requestId()))
                .satisfies(error -> assertThat(code(error)).isEqualTo(ErrorCode.INVALID_TRANSITION));
    }

    @Test
    void aConfirmationWithoutAStartedVerificationIsAnInvalidTransition() {
        DataSubjectRequest request = email("ACCESS", Optional.of(account));

        assertThatThrownBy(() -> confirm(request.requestId(), "valid:x"))
                .satisfies(error -> assertThat(code(error)).isEqualTo(ErrorCode.INVALID_TRANSITION));
    }

    @Test
    void anUnknownRequestIs404() {
        assertThatThrownBy(() -> start(UUID.randomUUID()))
                .satisfies(error -> assertThat(code(error)).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> confirm(UUID.randomUUID(), "valid:x"))
                .satisfies(error -> assertThat(code(error)).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void theSignatureIsNeverKeptNorPublished() {
        DataSubjectRequest request = email("ACCESS", Optional.of(account));
        IdentityVerification verification = start(request.requestId());
        String signature = "valid:" + verification.message();

        confirm(request.requestId(), signature);

        assertThat(people.checkedSignatures).containsExactly(signature);
        assertThat(store.verifications.get(request.requestId()).toString()).doesNotContain(signature);
        assertThat(events.published.toString()).doesNotContain("valid:");
        assertThat(store.requests.get(request.requestId()).toString()).doesNotContain("valid:");
    }

    @Test
    void aLoginShapedMessageSignatureNeverConfirms() {
        DataSubjectRequest request = email("ACCESS", Optional.of(account));
        start(request.requestId());
        String login = DOMAIN + " wants you to sign in with your Solana account:";

        assertThatThrownBy(() -> confirm(request.requestId(), "valid:" + login))
                .satisfies(error -> assertThat(code(error)).isEqualTo(ErrorCode.VERIFICATION_INVALID));
    }
}
