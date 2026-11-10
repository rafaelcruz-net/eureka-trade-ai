package com.memetrade.privacy.domain.request;

import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.IdempotencyRequest;
import com.memetrade.identity.api.IdempotencyScope;
import com.memetrade.identity.api.IdempotentOperation;
import com.memetrade.identity.api.IdempotentOutcome;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.identity.api.PersonDataPort;
import com.memetrade.identity.api.PersonDataPort.AccountRef;
import com.memetrade.identity.api.PersonDataPort.AccountState;
import com.memetrade.identity.api.Principal;
import com.memetrade.shared.error.FieldViolation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;

/**
 * The data subject requests (u4-data-requests; F1 to F3; BR4.1 to BR4.10, BR4.17). Each state change runs in ONE
 * transaction:
 *
 * <ol>
 *   <li>the body is checked first, so a 400 is never stored and never consumes an Idempotency-Key;
 *   <li>the common idempotency mechanism claims the key (same key and body replay the answer; another body is 409); any
 *       later failure (409, 422, 429) rolls back and releases the claim;
 *   <li>the person is limited per hour (creation only), the request is locked (transitions), the transition table is
 *       checked (BR4.5) and the change is written with its audit event (BR4.6).
 * </ol>
 *
 * Tenant and person always come from the token (P1) or from the linked account through J1 (P2); the text, the contact,
 * the response and the reason never reach a log, an event, a metric or an error (BR4.3).
 */
public class DataRequestService {

    /** BR4.1: the text of a request, counted in code points. */
    public static final int MAX_DETAILS = 1000;

    /** entities.md: the response of the administrator. */
    public static final int MAX_RESPONSE = 4000;

    /** entities.md: the reason of a refusal. */
    public static final int MAX_REFUSAL_REASON = 500;

    /** The longest e-mail address (RFC 5321 path limit). */
    public static final int MAX_CONTACT = 254;

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]+\\.[^\\s@]{2,}$");

    private final DataRequestStore store;
    private final VerificationStore verifications;
    private final IdempotencyGuard idempotency;
    private final DataRequestRateLimit rateLimit;
    private final DataRequestEvents events;
    private final PersonDataPort people;
    private final AccessSummarySource summaries;
    private final Clock clock;
    private final Duration responseTime;

    public DataRequestService(
            DataRequestStore store,
            VerificationStore verifications,
            IdempotencyGuard idempotency,
            DataRequestRateLimit rateLimit,
            DataRequestEvents events,
            PersonDataPort people,
            AccessSummarySource summaries,
            Clock clock,
            Duration responseTime) {
        this.store = Objects.requireNonNull(store, "store");
        this.verifications = Objects.requireNonNull(verifications, "verifications");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.rateLimit = Objects.requireNonNull(rateLimit, "rateLimit");
        this.events = Objects.requireNonNull(events, "events");
        this.people = Objects.requireNonNull(people, "people");
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.responseTime = Objects.requireNonNull(responseTime, "responseTime");
        if (responseTime.isZero() || responseTime.isNegative()) {
            throw new IllegalArgumentException("responseTime must be positive");
        }
    }

    // ------------------------------------------------------------------ P1: the person

    /** F1, BR4.1, BR4.2, NFR2.4: a request of the authenticated person. */
    @Transactional
    public Created create(NewRequest command) {
        Principal person = command.person();
        List<FieldViolation> violations = new ArrayList<>();
        Optional<DataRequestType> type = type(command.type(), violations);
        details(command.details(), violations);
        if (!violations.isEmpty()) {
            throw RequestErrors.validation(violations);
        }
        IdempotencyRequest claim = claim(
                IdempotentOperation.DATA_REQUEST_CREATE,
                person,
                command.key(),
                RequestDigests.digest(command.type(), command.details()));
        IdempotentResponse answer = idempotency.execute(claim, () -> {
            Instant now = clock.instant();
            rateLimit.refuse(person.tenantId(), person.userId(), now).ifPresent(wait -> {
                throw RequestErrors.rateLimited(wait);
            });
            if (store.existsOpen(person.tenantId(), person.userId(), type.get())) {
                throw RequestErrors.alreadyOpen();
            }
            DataSubjectRequest request = DataSubjectRequest.fromAccount(
                    UUID.randomUUID(),
                    person.tenantId(),
                    person.userId(),
                    type.get(),
                    command.details(),
                    now,
                    responseTime);
            insert(request);
            events.created(request, Actor.person(person));
            return IdempotentOutcome.of(IdempotentResponse.fresh(201, RequestDigests.stored(request.requestId())));
        });
        UUID requestId = RequestDigests.requestId(answer.body());
        DataSubjectRequest request = store.findOwned(person.tenantId(), person.userId(), requestId)
                .orElseThrow(() -> new IllegalStateException("A created request was not found again"));
        return new Created(request, answer.replayed());
    }

    /** F1 step 3, BR4.4: the requests of the person, newest first. */
    @Transactional(readOnly = true)
    public List<DataSubjectRequest> listMine(Principal person) {
        return store.listOwned(person.tenantId(), person.userId());
    }

    /** F1 step 4, BR4.4: a request of the person; any other is 404 without data. */
    @Transactional(readOnly = true)
    public DataSubjectRequest getMine(Principal person, UUID requestId) {
        return store.findOwned(person.tenantId(), person.userId(), requestId).orElseThrow(RequestErrors::notFound);
    }

    // ------------------------------------------------------------------ P2: the administrator

    /** F2, NFR3.2: one page of the administrator list, by due date. */
    @Transactional(readOnly = true)
    public List<DataSubjectRequest> page(
            Optional<DataRequestStatus> status, Optional<DataRequestStore.PageKey> after, int limit) {
        return store.page(status, after, limit);
    }

    /** F2 step 2, BR4.8: the request with its verification and, for an open ACCESS of an account, the preview. */
    @Transactional(readOnly = true)
    public Detail detail(UUID requestId) {
        DataSubjectRequest request = store.findById(requestId).orElseThrow(RequestErrors::notFound);
        Optional<AccessSummary> preview = Optional.empty();
        if (request.type() == DataRequestType.ACCESS
                && request.status().isOpen()
                && request.tenantId().isPresent()
                && request.userId().isPresent()) {
            preview = summaries.assemble(
                    request.tenantId().get(), request.userId().get());
        }
        return new Detail(request, verifications.find(requestId), preview);
    }

    /** F3 step 1, BR4.10, BR4.2: a request received by the e-mail of the DPO. */
    @Transactional
    public DataSubjectRequest registerEmail(EmailRequest command) {
        List<FieldViolation> violations = new ArrayList<>();
        Optional<DataRequestType> type = type(command.type(), violations);
        details(command.details(), violations);
        contact(command.contact(), violations);
        Optional<Instant> receivedAt = receivedAt(command.receivedAt(), violations);
        Optional<AccountRef> account = account(command.userId(), violations);
        if (!violations.isEmpty()) {
            throw RequestErrors.validation(violations);
        }
        IdempotencyRequest claim = claim(
                IdempotentOperation.DATA_REQUEST_REGISTER_EMAIL,
                command.admin(),
                command.key(),
                RequestDigests.digest(
                        command.type(), command.details(), command.contact(), command.receivedAt(), command.userId()));
        IdempotentResponse answer = idempotency.execute(claim, () -> {
            if (account.isPresent()
                    && store.existsOpen(account.get().tenantId(), account.get().userId(), type.get())) {
                throw RequestErrors.alreadyOpen();
            }
            DataSubjectRequest request = DataSubjectRequest.fromEmail(
                    UUID.randomUUID(),
                    account.map(ref -> new DataSubjectRequest.LinkedAccount(ref.tenantId(), ref.userId())),
                    type.get(),
                    command.details(),
                    command.contact().trim(),
                    receivedAt.get(),
                    responseTime);
            insert(request);
            events.created(request, Actor.admin(command.admin()));
            return IdempotentOutcome.of(IdempotentResponse.fresh(201, RequestDigests.stored(request.requestId())));
        });
        return reread(answer);
    }

    /** F2 step 3, BR4.5, BR4.8, BR4.9: a transition by the administrator. */
    @Transactional
    public DataSubjectRequest transition(Transition command) {
        DataRequestStatus to = target(command.to());
        List<FieldViolation> violations = new ArrayList<>();
        maxLength(command.response(), MAX_RESPONSE, "response", violations);
        maxLength(command.refusalReason(), MAX_REFUSAL_REASON, "refusalReason", violations);
        if (!violations.isEmpty()) {
            throw RequestErrors.validation(violations);
        }
        IdempotencyRequest claim = claim(
                IdempotentOperation.DATA_REQUEST_TRANSITION,
                command.admin(),
                command.key(),
                RequestDigests.digest(
                        command.requestId().toString(),
                        to.name(),
                        command.response(),
                        command.refusalReason(),
                        Boolean.toString(command.releaseAccessSummary())));
        IdempotentResponse answer = idempotency.execute(claim, () -> {
            DataSubjectRequest current = store.lockById(command.requestId()).orElseThrow(RequestErrors::notFound);
            if (current.status().isClosed()) {
                throw RequestErrors.closed();
            }
            DataSubjectRequest next = apply(current, to, command);
            store.update(next);
            events.transitioned(next, current.status(), Actor.admin(command.admin()));
            return IdempotentOutcome.of(IdempotentResponse.fresh(200, RequestDigests.stored(next.requestId())));
        });
        return reread(answer);
    }

    /** F3 step 5, BR4.17: the reply of a closed EMAIL request was sent to the contact; the first instant is kept. */
    @Transactional
    public DataSubjectRequest markReplySent(Principal admin, UUID requestId, IdempotencyKey key) {
        IdempotencyRequest claim = claim(
                IdempotentOperation.DATA_REQUEST_REPLY_SENT, admin, key, RequestDigests.digest(requestId.toString()));
        IdempotentResponse answer = idempotency.execute(claim, () -> {
            DataSubjectRequest current = store.lockById(requestId).orElseThrow(RequestErrors::notFound);
            if (current.origin() != DataRequestOrigin.EMAIL || current.status().isOpen()) {
                throw RequestErrors.invalidTransition("Only a closed EMAIL request records that its reply was sent.");
            }
            if (current.replySentAt().isEmpty()) {
                store.update(current.replySent(clock.instant()));
            }
            return IdempotentOutcome.of(IdempotentResponse.fresh(200, RequestDigests.stored(requestId)));
        });
        return reread(answer);
    }

    // ------------------------------------------------------------------ the transition table (BR4.5)

    private DataSubjectRequest apply(DataSubjectRequest current, DataRequestStatus to, Transition command) {
        Instant now = clock.instant();
        return switch (current.status()) {
            case RECEIVED -> {
                if (to != DataRequestStatus.IN_PROGRESS) {
                    throw RequestErrors.invalidTransition(current.status(), to.name());
                }
                if (needsVerificationFirst(current)) {
                    throw RequestErrors.invalidTransition(
                            "Identity verification first: an EMAIL ACCESS or DELETION request linked to an active"
                                    + " account goes to IN_PROGRESS only through the signature verification.");
                }
                yield current.inProgress(false);
            }
            case AWAITING_VERIFICATION -> {
                if (to != DataRequestStatus.REFUSED) {
                    throw RequestErrors.invalidTransition(
                            "AWAITING_VERIFICATION goes to IN_PROGRESS only through the confirmation of the"
                                    + " verification; the administrator may only refuse it.");
                }
                String reason = requireReason(command.refusalReason());
                verifications.expire(current.requestId());
                yield current.refused(reason, now);
            }
            case IN_PROGRESS -> {
                if (to == DataRequestStatus.DONE) {
                    yield done(current, command, now);
                }
                if (to == DataRequestStatus.REFUSED) {
                    yield current.refused(requireReason(command.refusalReason()), now);
                }
                throw RequestErrors.invalidTransition(current.status(), to.name());
            }
            case DONE, REFUSED -> throw RequestErrors.closed();
        };
    }

    private DataSubjectRequest done(DataSubjectRequest current, Transition command, Instant now) {
        Optional<String> response = Optional.ofNullable(command.response()).filter(text -> !text.isBlank());
        if (!command.releaseAccessSummary()) {
            return current.done(response, Optional.empty(), now);
        }
        if (current.type() != DataRequestType.ACCESS) {
            throw RequestErrors.unprocessable("releaseAccessSummary", "Only an ACCESS request releases a summary.");
        }
        if (current.origin() == DataRequestOrigin.EMAIL && !current.identityVerified()) {
            throw RequestErrors.identityNotVerified();
        }
        if (current.tenantId().isEmpty() || current.userId().isEmpty()) {
            throw RequestErrors.unprocessable(
                    "releaseAccessSummary", "The request is not linked to an account with a summary.");
        }
        Optional<AccessSummary> summary =
                summaries.assemble(current.tenantId().get(), current.userId().get());
        if (summary.isEmpty()) {
            throw RequestErrors.unprocessable(
                    "releaseAccessSummary", "The account of the request has no summary any more.");
        }
        return current.done(response, summary, now);
    }

    /** BR4.5: an EMAIL ACCESS or DELETION linked to an ACTIVE account confirms the identity first (BR4.11). */
    private boolean needsVerificationFirst(DataSubjectRequest request) {
        if (request.origin() != DataRequestOrigin.EMAIL
                || !request.type().needsVerification()
                || request.userId().isEmpty()) {
            return false;
        }
        return people.findAccount(request.userId().get())
                .map(account -> account.state() == AccountState.ACTIVE)
                .orElse(false);
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw RequestErrors.unprocessable("refusalReason", "A refusal needs its reason.");
        }
        return reason.trim();
    }

    // ------------------------------------------------------------------ helpers

    private void insert(DataSubjectRequest request) {
        try {
            store.insert(request);
        } catch (OpenRequestConflictException race) {
            // NFR3.8: the unique index decided a race; the answer is the same as the check above.
            throw RequestErrors.alreadyOpen();
        }
    }

    private DataSubjectRequest reread(IdempotentResponse answer) {
        UUID requestId = RequestDigests.requestId(answer.body());
        return store.findById(requestId).orElseThrow(RequestErrors::notFound);
    }

    private static IdempotencyRequest claim(
            IdempotentOperation operation, Principal principal, IdempotencyKey key, String digest) {
        return new IdempotencyRequest(operation, IdempotencyScope.authenticated(principal, operation), key, digest);
    }

    private static Optional<DataRequestType> type(String value, List<FieldViolation> violations) {
        Optional<DataRequestType> type = DataRequestType.parse(value);
        if (type.isEmpty()) {
            violations.add(new FieldViolation("type", "Must be one of ACCESS, CORRECTION, DELETION, OTHER."));
        }
        return type;
    }

    private static void details(String value, List<FieldViolation> violations) {
        if (value == null || value.isBlank()) {
            violations.add(new FieldViolation("details", "Must not be empty."));
        } else if (value.codePointCount(0, value.length()) > MAX_DETAILS) {
            violations.add(new FieldViolation("details", "Must have at most 1000 characters."));
        }
    }

    private static void contact(String value, List<FieldViolation> violations) {
        if (value == null
                || value.isBlank()
                || value.trim().length() > MAX_CONTACT
                || !EMAIL.matcher(value.trim()).matches()) {
            violations.add(new FieldViolation("contact", "Must be an e-mail address."));
        }
    }

    private Optional<Instant> receivedAt(String value, List<FieldViolation> violations) {
        if (value == null || value.isBlank()) {
            violations.add(new FieldViolation("receivedAt", "Is required."));
            return Optional.empty();
        }
        try {
            Instant parsed = OffsetDateTime.parse(value.trim()).toInstant();
            if (parsed.isAfter(clock.instant())) {
                violations.add(new FieldViolation("receivedAt", "Must not be in the future."));
                return Optional.empty();
            }
            return Optional.of(parsed);
        } catch (DateTimeParseException notADate) {
            violations.add(new FieldViolation("receivedAt", "Must be an ISO-8601 date and time with its offset."));
            return Optional.empty();
        }
    }

    private Optional<AccountRef> account(String value, List<FieldViolation> violations) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        UUID userId;
        try {
            userId = UUID.fromString(value.trim());
        } catch (IllegalArgumentException notAUuid) {
            violations.add(new FieldViolation("userId", "Must be a UUID."));
            return Optional.empty();
        }
        Optional<AccountRef> account = people.findAccount(userId);
        if (account.isEmpty()) {
            violations.add(new FieldViolation("userId", "Must name an existing account."));
        }
        return account;
    }

    private static DataRequestStatus target(String value) {
        Optional<DataRequestStatus> status = DataRequestStatus.parse(value)
                .filter(candidate -> candidate == DataRequestStatus.IN_PROGRESS
                        || candidate == DataRequestStatus.DONE
                        || candidate == DataRequestStatus.REFUSED);
        return status.orElseThrow(() -> RequestErrors.validation(
                List.of(new FieldViolation("to", "Must be one of IN_PROGRESS, DONE, REFUSED."))));
    }

    private static void maxLength(String value, int max, String field, List<FieldViolation> violations) {
        if (value != null && value.codePointCount(0, value.length()) > max) {
            violations.add(new FieldViolation(field, "Must have at most " + max + " characters."));
        }
    }

    // ------------------------------------------------------------------ commands and results

    /** A request of the person: the type and the text as sent, never trusted. */
    public record NewRequest(Principal person, String type, String details, IdempotencyKey key) {
        public NewRequest {
            Objects.requireNonNull(person, "person");
            Objects.requireNonNull(key, "key");
        }

        @Override
        public String toString() {
            return "NewRequest[type=" + type + "]";
        }
    }

    /** The created request; {@code replayed} when the Idempotency-Key answered a previous call. */
    public record Created(DataSubjectRequest request, boolean replayed) {}

    /** The administrator view of one request (P2 {@code AdminDataRequest}). */
    public record Detail(
            DataSubjectRequest request, Optional<IdentityVerification> verification, Optional<AccessSummary> preview) {}

    /** An EMAIL request registered by the administrator; every value as sent. */
    public record EmailRequest(
            Principal admin,
            String type,
            String details,
            String contact,
            String receivedAt,
            String userId,
            IdempotencyKey key) {
        public EmailRequest {
            Objects.requireNonNull(admin, "admin");
            Objects.requireNonNull(key, "key");
        }

        @Override
        public String toString() {
            return "EmailRequest[type=" + type + "]";
        }
    }

    /** A transition by the administrator. */
    public record Transition(
            Principal admin,
            UUID requestId,
            String to,
            String response,
            String refusalReason,
            boolean releaseAccessSummary,
            IdempotencyKey key) {
        public Transition {
            Objects.requireNonNull(admin, "admin");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(key, "key");
        }

        @Override
        public String toString() {
            return "Transition[requestId=" + requestId + ", to=" + to + "]";
        }
    }
}
