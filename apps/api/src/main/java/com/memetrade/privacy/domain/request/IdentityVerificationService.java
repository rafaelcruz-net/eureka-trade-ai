package com.memetrade.privacy.domain.request;

import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.IdempotencyRequest;
import com.memetrade.identity.api.IdempotencyScope;
import com.memetrade.identity.api.IdempotentOperation;
import com.memetrade.identity.api.IdempotentOutcome;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.identity.api.PersonDataPort;
import com.memetrade.identity.api.PersonDataPort.AccountState;
import com.memetrade.identity.api.Principal;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signature verification of an EMAIL request (F3; BR4.11, BR4.12; NFR2.3). Each call runs in ONE transaction with
 * the request locked:
 *
 * <ul>
 *   <li><b>start</b>: only an EMAIL ACCESS or DELETION linked to an ACTIVE account, in RECEIVED; a unique message, valid
 *       {@code validity} (24 hours), and the request goes to AWAITING_VERIFICATION;
 *   <li><b>confirm</b>: a closed request is 409 DATA_REQUEST_CLOSED before anything else; a CONFIRMED verification is
 *       409 VERIFICATION_ALREADY_USED; an expired one 409 VERIFICATION_EXPIRED; a signature J1 refuses is 422
 *       VERIFICATION_INVALID and the request keeps waiting; a valid one confirms conditionally on PENDING and not expired
 *       (so the expiration job and a second confirmation never both win) and the request goes to IN_PROGRESS with its
 *       identity verified.
 * </ul>
 *
 * The signature is only handed to J1; it is never stored, logged, published nor put in an error.
 */
public class IdentityVerificationService {

    private final DataRequestStore store;
    private final VerificationStore verifications;
    private final IdempotencyGuard idempotency;
    private final DataRequestEvents events;
    private final PersonDataPort people;
    private final Clock clock;
    private final Duration validity;
    private final String domain;

    public IdentityVerificationService(
            DataRequestStore store,
            VerificationStore verifications,
            IdempotencyGuard idempotency,
            DataRequestEvents events,
            PersonDataPort people,
            Clock clock,
            Duration validity,
            String domain) {
        this.store = Objects.requireNonNull(store, "store");
        this.verifications = Objects.requireNonNull(verifications, "verifications");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.events = Objects.requireNonNull(events, "events");
        this.people = Objects.requireNonNull(people, "people");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.validity = Objects.requireNonNull(validity, "validity");
        this.domain = Objects.requireNonNull(domain, "domain");
        if (validity.isZero() || validity.isNegative()) {
            throw new IllegalArgumentException("validity must be positive");
        }
    }

    /** F3 step 2, BR4.11. */
    @Transactional
    public IdentityVerification start(Principal admin, UUID requestId, IdempotencyKey key) {
        IdempotencyRequest claim = claim(
                IdempotentOperation.DATA_REQUEST_VERIFICATION_START,
                admin,
                key,
                RequestDigests.digest(requestId.toString()));
        IdempotentResponse answer = idempotency.execute(claim, () -> {
            DataSubjectRequest current = store.lockById(requestId).orElseThrow(RequestErrors::notFound);
            if (current.status().isClosed()) {
                throw RequestErrors.closed();
            }
            requireVerifiable(current);
            Instant now = clock.instant();
            Instant expiresAt = now.plus(validity);
            verifications.insert(new IdentityVerification(
                    requestId,
                    VerificationMessage.build(domain, requestId, expiresAt),
                    now,
                    expiresAt,
                    VerificationState.PENDING,
                    Optional.empty()));
            DataSubjectRequest awaiting = current.awaitingVerification();
            store.update(awaiting);
            events.verificationStarted(awaiting, Actor.admin(admin));
            return IdempotentOutcome.of(IdempotentResponse.fresh(200, RequestDigests.stored(requestId)));
        });
        UUID stored = RequestDigests.requestId(answer.body());
        return verifications
                .find(stored)
                .orElseThrow(() -> new IllegalStateException("A started verification was not found again"));
    }

    /** F3 step 3, BR4.12. */
    @Transactional
    public DataSubjectRequest confirm(Principal admin, UUID requestId, String signature, IdempotencyKey key) {
        IdempotencyRequest claim = claim(
                IdempotentOperation.DATA_REQUEST_VERIFICATION_CONFIRM,
                admin,
                key,
                // The digest of the signature, never the signature itself, decides a repeated key.
                RequestDigests.digest(requestId.toString(), signature));
        IdempotentResponse answer = idempotency.execute(claim, () -> {
            DataSubjectRequest current = store.lockById(requestId).orElseThrow(RequestErrors::notFound);
            if (current.status().isClosed()) {
                throw RequestErrors.closed();
            }
            IdentityVerification verification = verifications
                    .find(requestId)
                    .orElseThrow(
                            () -> RequestErrors.invalidTransition("The request has no verification; start one first."));
            Instant now = clock.instant();
            requireUsable(verification, now);
            if (current.status() != DataRequestStatus.AWAITING_VERIFICATION) {
                throw RequestErrors.invalidTransition(current.status(), DataRequestStatus.IN_PROGRESS.name());
            }
            UUID account = current.userId()
                    .orElseThrow(() -> RequestErrors.invalidTransition("The request is not linked to an account."));
            if (!people.verifyAccountSignature(account, verification.message(), signature)) {
                throw new ApiException(
                        ErrorCode.VERIFICATION_INVALID,
                        "The signature is not the signature of the wallet of the account over the message.");
            }
            if (!verifications.confirm(requestId, now)) {
                // A concurrent confirmation or the expiration job won: report what it left.
                requireUsable(verifications.find(requestId).orElseThrow(), now);
                throw RequestErrors.invalidTransition(current.status(), DataRequestStatus.IN_PROGRESS.name());
            }
            DataSubjectRequest confirmed = current.inProgress(true);
            store.update(confirmed);
            events.verified(confirmed, Actor.admin(admin));
            return IdempotentOutcome.of(IdempotentResponse.fresh(200, RequestDigests.stored(requestId)));
        });
        return store.findById(RequestDigests.requestId(answer.body())).orElseThrow(RequestErrors::notFound);
    }

    /** BR4.11: EMAIL, linked to an ACTIVE account, ACCESS or DELETION, RECEIVED; anything else 409. */
    private void requireVerifiable(DataSubjectRequest request) {
        if (request.origin() != DataRequestOrigin.EMAIL) {
            throw RequestErrors.invalidTransition("Only an EMAIL request is verified by signature.");
        }
        if (!request.type().needsVerification()) {
            throw RequestErrors.invalidTransition("Only an ACCESS or DELETION request is verified by signature.");
        }
        if (request.userId().isEmpty()) {
            throw RequestErrors.invalidTransition("The request is not linked to an account.");
        }
        boolean active = people.findAccount(request.userId().get())
                .map(account -> account.state() == AccountState.ACTIVE)
                .orElse(false);
        if (!active) {
            throw RequestErrors.invalidTransition("The linked account is anonymized; it cannot sign.");
        }
        if (request.status() != DataRequestStatus.RECEIVED) {
            throw RequestErrors.invalidTransition(request.status(), DataRequestStatus.AWAITING_VERIFICATION.name());
        }
    }

    private static void requireUsable(IdentityVerification verification, Instant now) {
        if (verification.state() == VerificationState.CONFIRMED) {
            throw new ApiException(ErrorCode.VERIFICATION_ALREADY_USED, "The verification was already confirmed.");
        }
        if (verification.state() == VerificationState.EXPIRED || verification.isExpiredAt(now)) {
            throw new ApiException(ErrorCode.VERIFICATION_EXPIRED, "The verification message expired.");
        }
    }

    private static IdempotencyRequest claim(
            IdempotentOperation operation, Principal principal, IdempotencyKey key, String digest) {
        return new IdempotencyRequest(operation, IdempotencyScope.authenticated(principal, operation), key, digest);
    }
}
