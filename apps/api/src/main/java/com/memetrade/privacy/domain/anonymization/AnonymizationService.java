package com.memetrade.privacy.domain.anonymization;

import com.memetrade.bots.api.BotOwnerDataPort;
import com.memetrade.feedback.api.FeedbackOwnerDataPort;
import com.memetrade.identity.api.AccountAnonymizationPort;
import com.memetrade.identity.api.AccountAnonymizationPort.AccountAnonymization;
import com.memetrade.identity.api.AccountAnonymizationPort.AnonymizationTarget;
import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.IdempotencyRequest;
import com.memetrade.identity.api.IdempotencyScope;
import com.memetrade.identity.api.IdempotentOperation;
import com.memetrade.identity.api.IdempotentOutcome;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.privacy.domain.request.Actor;
import com.memetrade.privacy.domain.request.DataRequestEvents;
import com.memetrade.privacy.domain.request.DataRequestOrigin;
import com.memetrade.privacy.domain.request.DataRequestStatus;
import com.memetrade.privacy.domain.request.DataRequestStore;
import com.memetrade.privacy.domain.request.DataRequestType;
import com.memetrade.privacy.domain.request.DataSubjectRequest;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.FieldViolation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.transaction.annotation.Transactional;

/**
 * The anonymization of the account of a DELETION request (u5-anonymization; F1, F2, F3; BR5.1 to BR5.6), in ONE
 * transaction opened here:
 *
 * <ol>
 *   <li>the exact confirmation {@code ANONIMIZAR} (422 CONFIRMATION_REQUIRED), before anything is read or stored;
 *   <li>the common idempotency mechanism claims the key ({@code DATA_REQUEST_ANONYMIZE}); a refusal or a failure is never
 *       stored, so the same key may be repeated after a 503;
 *   <li>the {@code lock_timeout} of the bots commands, then the request locked ({@code FOR UPDATE}): two anonymizations of
 *       the same request are serialised, and the second answers as a repetition (NFR5.5);
 *   <li>a DELETION whose account is already ANONYMIZED is a repetition: the same request, person and instant, nothing
 *       written and no event (BR5.5);
 *   <li>the checks of BR5.1 in the order of security-design.md: DELETION and IN_PROGRESS (409 REQUEST_NOT_ELIGIBLE), the
 *       linked account (409 NO_LINKED_ACCOUNT), a BETA_USER (409 ADMIN_ACCOUNT_NOT_ANONYMIZABLE), the confirmed identity
 *       of an EMAIL request (409 IDENTITY_NOT_VERIFIED, AC6.1.2); nothing changes on a refusal;
 *   <li>J2 (bots), J3 (feedback), J1 (account, in the lock order sessions then account), J6 (ACCOUNT_ANONYMIZED) and the
 *       conclusion of the request (DONE with the model response, DATA_REQUEST_TRANSITIONED).
 * </ol>
 *
 * Any exception rolls everything back and the request stays IN_PROGRESS; a lock not obtained in time is 503
 * LOCK_TIMEOUT. Tenant and person come from the request, never from the body. Nothing here logs; the attempt is
 * observed by {@link AnonymizationOutcomes} after the commit or the rollback, with identifiers and counts only.
 */
public class AnonymizationService {

    /** BR5.1, BR5.9: the exact text the administrator types. */
    public static final String CONFIRMATION = "ANONIMIZAR";

    /** The model response of a DELETION concluded by the anonymization (the same text as the administrator app). */
    public static final String DELETION_RESPONSE = "Sua conta foi anonimizada a seu pedido. Saíram a carteira, o"
            + " provedor, as sessões, os seus comentários e os nomes dos seus bots. Ficam, sem ligação com a sua"
            + " carteira, o histórico PAPER, o ledger e os aceites dos termos, pelos prazos da política de"
            + " privacidade.";

    /** NFR5.4: the answer to a lock timeout suggests waiting the lock timeout itself before the repetition. */
    static final String RETRY_AFTER_SECONDS = "2";

    private final DataRequestStore requests;
    private final IdempotencyGuard idempotency;
    private final AccountAnonymizationPort accounts;
    private final BotOwnerDataPort bots;
    private final FeedbackOwnerDataPort feedback;
    private final AnonymizationEvents events;
    private final DataRequestEvents requestEvents;
    private final AnonymizationOutcomes outcomes;
    private final AnonymizationTimeouts timeouts;
    private final Clock clock;

    public AnonymizationService(
            DataRequestStore requests,
            IdempotencyGuard idempotency,
            AccountAnonymizationPort accounts,
            BotOwnerDataPort bots,
            FeedbackOwnerDataPort feedback,
            AnonymizationEvents events,
            DataRequestEvents requestEvents,
            AnonymizationOutcomes outcomes,
            AnonymizationTimeouts timeouts,
            Clock clock) {
        this.requests = Objects.requireNonNull(requests, "requests");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.bots = Objects.requireNonNull(bots, "bots");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.events = Objects.requireNonNull(events, "events");
        this.requestEvents = Objects.requireNonNull(requestEvents, "requestEvents");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** P2 {@code adminAnonymizeAccount}. */
    @Transactional
    public AnonymizationResult anonymize(Anonymize command) {
        if (!CONFIRMATION.equals(command.confirmation())) {
            throw confirmationRequired();
        }
        IdempotentOperation operation = IdempotentOperation.DATA_REQUEST_ANONYMIZE;
        IdempotencyRequest claim = new IdempotencyRequest(
                operation,
                IdempotencyScope.authenticated(command.admin(), operation),
                command.key(),
                digest(command.requestId().toString(), CONFIRMATION));
        AnonymizationOutcomes.Attempt attempt = outcomes.start(command.requestId());
        // The failure inside the action is kept: after a SQL error the transaction is aborted and the release of the
        // claim fails too, hiding the cause (a lock timeout must still be recognised).
        AtomicReference<RuntimeException> inside = new AtomicReference<>();
        IdempotentResponse answer;
        try {
            answer = idempotency.execute(claim, () -> {
                try {
                    return IdempotentOutcome.of(
                            IdempotentResponse.fresh(200, run(command, attempt).toStored()));
                } catch (RuntimeException failure) {
                    inside.set(failure);
                    throw failure;
                }
            });
        } catch (RuntimeException failure) {
            throw classify(inside.get() != null ? inside.get() : failure, attempt);
        }
        if (answer.replayed()) {
            attempt.repeated();
        }
        return AnonymizationResult.fromStored(answer.body());
    }

    private AnonymizationResult run(Anonymize command, AnonymizationOutcomes.Attempt attempt) {
        timeouts.applyLockTimeout();
        DataSubjectRequest request = requests.lockById(command.requestId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Data request not found."));
        Optional<AnonymizationTarget> target = request.userId().flatMap(accounts::target);
        if (request.type() == DataRequestType.DELETION
                && target.isPresent()
                && target.get().anonymized()) {
            Instant anonymizedAt = target.get().anonymizedAt().orElseGet(clock::instant);
            // BR5.5: the repetition of a concluded deletion answers the same result; another deletion still in
            // progress is concluded without touching the account again; any other status stays not eligible.
            if (request.status() == DataRequestStatus.DONE) {
                attempt.repeated();
                return AnonymizationResult.repetition(
                        request.requestId(), target.get().userId(), anonymizedAt);
            }
            if (request.status() == DataRequestStatus.IN_PROGRESS) {
                return concludeRepetition(request, target.get().userId(), anonymizedAt, command.admin(), attempt);
            }
        }
        AnonymizationTarget account = eligible(request, target);
        return anonymize(request, account, command.admin(), attempt);
    }

    /** A deletion in progress whose account is already anonymized: concluded, with no account write and no J6. */
    private AnonymizationResult concludeRepetition(
            DataSubjectRequest request,
            UUID userId,
            Instant anonymizedAt,
            Principal admin,
            AnonymizationOutcomes.Attempt attempt) {
        DataSubjectRequest done = request.done(Optional.of(DELETION_RESPONSE), Optional.empty(), clock.instant());
        requests.update(done);
        requestEvents.transitioned(done, request.status(), Actor.admin(admin));
        attempt.repeated();
        return AnonymizationResult.repetition(request.requestId(), userId, anonymizedAt);
    }

    /** BR5.1, in the order of security-design.md NFR2.1. */
    private static AnonymizationTarget eligible(DataSubjectRequest request, Optional<AnonymizationTarget> target) {
        if (request.type() != DataRequestType.DELETION || request.status() != DataRequestStatus.IN_PROGRESS) {
            throw new ApiException(
                    ErrorCode.REQUEST_NOT_ELIGIBLE,
                    "Only a DELETION request IN_PROGRESS is anonymized; this one is " + request.type() + " "
                            + request.status() + ".");
        }
        if (target.isEmpty()
                || request.tenantId().isEmpty()
                || !request.tenantId().get().equals(target.get().tenantId())) {
            throw new ApiException(ErrorCode.NO_LINKED_ACCOUNT, "The request is not linked to an account.");
        }
        if (target.get().role() != Role.BETA_USER) {
            throw new ApiException(
                    ErrorCode.ADMIN_ACCOUNT_NOT_ANONYMIZABLE, "An administrator account is never anonymized.");
        }
        if (request.origin() == DataRequestOrigin.EMAIL && !request.identityVerified()) {
            throw new ApiException(
                    ErrorCode.IDENTITY_NOT_VERIFIED,
                    "An EMAIL request needs a confirmed identity before the anonymization.");
        }
        return target.get();
    }

    /** BR5.2: J2, J3, J1, J6 and the conclusion, in this order, in the transaction of {@link #anonymize}. */
    private AnonymizationResult anonymize(
            DataSubjectRequest request,
            AnonymizationTarget account,
            Principal admin,
            AnonymizationOutcomes.Attempt attempt) {
        UUID tenantId = account.tenantId();
        UUID userId = account.userId();
        Instant now = clock.instant();
        int stopped = bots.anonymizeOwnerBots(tenantId, userId);
        int erased = feedback.deleteByUser(tenantId, userId);
        AccountAnonymization anonymized = accounts.anonymizeAccount(tenantId, userId, now);
        if (anonymized.alreadyAnonymized()) {
            // Another request of the same person anonymized the account in between: this one is concluded too.
            return concludeRepetition(request, userId, anonymized.anonymizedAt(), admin, attempt);
        }
        AnonymizationResult result = new AnonymizationResult(
                request.requestId(),
                userId,
                anonymized.anonymizedAt(),
                anonymized.sessionsRevoked(),
                stopped,
                erased,
                false);
        events.accountAnonymized(request, result, admin);
        DataSubjectRequest done = request.done(Optional.of(DELETION_RESPONSE), Optional.empty(), now);
        requests.update(done);
        requestEvents.transitioned(done, request.status(), Actor.admin(admin));
        attempt.succeeded(result);
        return result;
    }

    private static RuntimeException classify(RuntimeException failure, AnonymizationOutcomes.Attempt attempt) {
        if (failure instanceof ApiException refusal) {
            attempt.refused();
            return refusal;
        }
        if (LockTimeouts.isLockTimeout(failure)) {
            attempt.failed(AnonymizationOutcomes.FailureReason.LOCK_TIMEOUT, failure);
            return new ApiException(
                    ErrorCode.LOCK_TIMEOUT,
                    "A lock was not obtained in time; nothing changed. Repeat with the same Idempotency-Key.",
                    List.of(),
                    null,
                    Map.of("Retry-After", RETRY_AFTER_SECONDS));
        }
        attempt.failed(AnonymizationOutcomes.FailureReason.ROLLED_BACK, failure);
        return failure;
    }

    private static ApiException confirmationRequired() {
        return new ApiException(
                ErrorCode.CONFIRMATION_REQUIRED,
                "Type ANONIMIZAR to confirm the anonymization.",
                List.of(new FieldViolation("confirmation", "Must be exactly ANONIMIZAR.")),
                null,
                Map.of());
    }

    private static String digest(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                sha.update(part.getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0x1f);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", impossible);
        }
    }

    /** The call of the administrator: the confirmation as typed, never trusted. */
    public record Anonymize(Principal admin, UUID requestId, String confirmation, IdempotencyKey key) {
        public Anonymize {
            Objects.requireNonNull(admin, "admin");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(key, "key");
        }

        @Override
        public String toString() {
            return "Anonymize[requestId=" + requestId + "]";
        }
    }
}
