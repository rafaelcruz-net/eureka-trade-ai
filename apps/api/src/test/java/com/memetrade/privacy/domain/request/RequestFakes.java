package com.memetrade.privacy.domain.request;

import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.api.IdempotencyRequest;
import com.memetrade.identity.api.IdempotentOutcome;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.identity.api.PersonDataPort;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * u4-data-requests (Passo 3): in-memory doubles of the ports of the request domain, with the SAME conditions the
 * database enforces (one open request per person and type, conditional updates on the state), so the rule tests of
 * BR4.1 to BR4.15 run without a database. The SQL of the real stores is proved by the integration tests.
 */
final class RequestFakes {

    private RequestFakes() {}

    /** The request table and the verification table together, like the JDBC stores. */
    static class Store implements DataRequestStore, VerificationStore, DataRequestPurgeStore {

        final Map<UUID, DataSubjectRequest> requests = new LinkedHashMap<>();
        final Map<UUID, IdentityVerification> verifications = new LinkedHashMap<>();
        int locks;

        @Override
        public void insert(DataSubjectRequest request) {
            if (request.userId().isPresent()
                    && existsOpen(
                            request.tenantId().orElseThrow(), request.userId().get(), request.type())) {
                throw new OpenRequestConflictException();
            }
            requests.put(request.requestId(), request);
        }

        @Override
        public boolean existsOpen(UUID tenantId, UUID userId, DataRequestType type) {
            return requests.values().stream()
                    .anyMatch(r -> r.tenantId().equals(Optional.of(tenantId))
                            && r.userId().equals(Optional.of(userId))
                            && r.type() == type
                            && r.status().isOpen());
        }

        @Override
        public Optional<DataSubjectRequest> findOwned(UUID tenantId, UUID userId, UUID requestId) {
            return Optional.ofNullable(requests.get(requestId))
                    .filter(r -> r.tenantId().equals(Optional.of(tenantId))
                            && r.userId().equals(Optional.of(userId)));
        }

        @Override
        public List<DataSubjectRequest> listOwned(UUID tenantId, UUID userId) {
            return requests.values().stream()
                    .filter(r -> r.tenantId().equals(Optional.of(tenantId))
                            && r.userId().equals(Optional.of(userId)))
                    .sorted(Comparator.comparing(DataSubjectRequest::receivedAt).reversed())
                    .toList();
        }

        @Override
        public Optional<DataSubjectRequest> findById(UUID requestId) {
            return Optional.ofNullable(requests.get(requestId));
        }

        @Override
        public Optional<DataSubjectRequest> lockById(UUID requestId) {
            locks++;
            return findById(requestId);
        }

        @Override
        public void update(DataSubjectRequest request) {
            if (!requests.containsKey(request.requestId())) {
                throw new IllegalStateException("update of a request that does not exist");
            }
            requests.put(request.requestId(), request);
        }

        @Override
        public List<DataSubjectRequest> page(Optional<DataRequestStatus> status, Optional<PageKey> after, int limit) {
            return requests.values().stream()
                    .filter(r -> status.map(s -> r.status() == s).orElse(true))
                    .sorted(Comparator.comparing(DataSubjectRequest::dueAt)
                            .thenComparing(DataSubjectRequest::requestId))
                    .filter(r -> after.map(key -> r.dueAt().isAfter(key.dueAt())
                                    || (r.dueAt().equals(key.dueAt())
                                            && r.requestId().compareTo(key.requestId()) > 0))
                            .orElse(true))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<DataSubjectRequest> refuseExpiredVerifications(Instant now, String reason, int limit) {
            List<DataSubjectRequest> refused = new ArrayList<>();
            for (IdentityVerification verification : List.copyOf(verifications.values())) {
                if (refused.size() == limit) {
                    break;
                }
                DataSubjectRequest request = requests.get(verification.requestId());
                if (verification.state() == VerificationState.PENDING
                        && !verification.expiresAt().isAfter(now)
                        && request.status() == DataRequestStatus.AWAITING_VERIFICATION) {
                    verifications.put(verification.requestId(), verification.expired());
                    DataSubjectRequest closed = request.refused(reason, now);
                    requests.put(closed.requestId(), closed);
                    refused.add(closed);
                }
            }
            return refused;
        }

        @Override
        public List<DataSubjectRequest> markOverdue(Instant now, int limit) {
            List<DataSubjectRequest> marked = new ArrayList<>();
            for (DataSubjectRequest request : List.copyOf(requests.values())) {
                if (marked.size() == limit) {
                    break;
                }
                if (request.status().isOpen()
                        && !request.dueAt().isAfter(now)
                        && request.overdueNotifiedAt().isEmpty()) {
                    DataSubjectRequest notified = request.overdueNotified(now);
                    requests.put(notified.requestId(), notified);
                    marked.add(notified);
                }
            }
            return marked;
        }

        @Override
        public void insert(IdentityVerification verification) {
            if (verifications.containsKey(verification.requestId())) {
                throw new IllegalStateException("one verification per request");
            }
            verifications.put(verification.requestId(), verification);
        }

        @Override
        public Optional<IdentityVerification> find(UUID requestId) {
            return Optional.ofNullable(verifications.get(requestId));
        }

        @Override
        public boolean confirm(UUID requestId, Instant now) {
            IdentityVerification current = verifications.get(requestId);
            if (current == null
                    || current.state() != VerificationState.PENDING
                    || !current.expiresAt().isAfter(now)) {
                return false;
            }
            verifications.put(requestId, current.confirmed(now));
            return true;
        }

        @Override
        public boolean expire(UUID requestId) {
            IdentityVerification current = verifications.get(requestId);
            if (current == null || current.state() != VerificationState.PENDING) {
                return false;
            }
            verifications.put(requestId, current.expired());
            return true;
        }

        @Override
        public PurgeBatch deleteClosedUpTo(Instant cutoff, int limit) {
            List<UUID> gone = requests.values().stream()
                    .filter(r -> r.closedAt().map(at -> !at.isAfter(cutoff)).orElse(false))
                    .sorted(Comparator.comparing(r -> r.closedAt().orElseThrow()))
                    .limit(limit)
                    .map(DataSubjectRequest::requestId)
                    .toList();
            int related = 0;
            for (UUID id : gone) {
                if (verifications.remove(id) != null) {
                    related++;
                }
                requests.remove(id);
            }
            return new PurgeBatch(gone.size(), related);
        }
    }

    /** The common idempotency mechanism: same key and digest replay; another digest is 409; a failure stores nothing. */
    static final class Idempotency implements IdempotencyGuard {

        private final Map<String, String> digests = new HashMap<>();
        private final Map<String, IdempotentResponse> responses = new HashMap<>();
        int executions;

        @Override
        public IdempotentResponse execute(IdempotencyRequest request, Supplier<IdempotentOutcome> action) {
            String slot = request.scope().value() + "|" + request.key().value();
            if (responses.containsKey(slot)) {
                if (!digests.get(slot).equals(request.requestDigest())) {
                    throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "Key reused with another body.");
                }
                IdempotentResponse stored = responses.get(slot);
                return new IdempotentResponse(stored.status(), stored.body(), true);
            }
            executions++;
            IdempotentResponse fresh = action.get().response();
            digests.put(slot, request.requestDigest());
            responses.put(slot, fresh);
            return fresh;
        }
    }

    /** One event as published, with the actor kind. */
    record Event(String kind, UUID requestId, Optional<DataRequestStatus> from, DataRequestStatus to, String actor) {}

    static final class Events implements DataRequestEvents {

        final List<Event> published = new ArrayList<>();

        @Override
        public void created(DataSubjectRequest request, Actor actor) {
            published.add(new Event("CREATED", request.requestId(), Optional.empty(), request.status(), actor.kind()));
        }

        @Override
        public void transitioned(DataSubjectRequest request, DataRequestStatus from, Actor actor) {
            published.add(
                    new Event("TRANSITIONED", request.requestId(), Optional.of(from), request.status(), actor.kind()));
        }

        @Override
        public void verificationStarted(DataSubjectRequest request, Actor actor) {
            published.add(new Event(
                    "VERIFICATION_STARTED",
                    request.requestId(),
                    Optional.of(DataRequestStatus.RECEIVED),
                    request.status(),
                    actor.kind()));
        }

        @Override
        public void verified(DataSubjectRequest request, Actor actor) {
            published.add(new Event(
                    "VERIFIED",
                    request.requestId(),
                    Optional.of(DataRequestStatus.AWAITING_VERIFICATION),
                    request.status(),
                    actor.kind()));
        }

        @Override
        public void overdue(DataSubjectRequest request) {
            published.add(new Event("OVERDUE", request.requestId(), Optional.empty(), request.status(), "SYSTEM"));
        }

        List<String> kinds() {
            return published.stream().map(Event::kind).toList();
        }
    }

    /** The limit per hour: refuses when told to, with a fixed Retry-After. */
    static final class RateLimit implements DataRequestRateLimit {

        Optional<Duration> refusal = Optional.empty();
        int checks;

        @Override
        public Optional<Duration> refuse(UUID tenantId, UUID userId, Instant now) {
            checks++;
            return refusal;
        }
    }

    /** J1 with known accounts; a signature is valid only when it equals {@code "valid:" + message}. */
    static final class People implements PersonDataPort {

        final Map<UUID, AccountRef> accounts = new HashMap<>();
        final List<String> checkedSignatures = new ArrayList<>();

        UUID account(UUID tenantId, AccountState state) {
            UUID userId = UUID.randomUUID();
            accounts.put(userId, new AccountRef(userId, tenantId, state));
            return userId;
        }

        @Override
        public Optional<PersonSummary> personSummary(UUID tenantId, UUID userId) {
            return Optional.ofNullable(accounts.get(userId))
                    .filter(account -> account.tenantId().equals(tenantId) && account.state() == AccountState.ACTIVE)
                    .map(account -> new PersonSummary(
                            "Abcd…wxyz",
                            "PHANTOM",
                            Instant.parse("2026-01-01T00:00:00Z"),
                            Optional.empty(),
                            List.of()));
        }

        @Override
        public Optional<AccountRef> findAccount(UUID userId) {
            return Optional.ofNullable(accounts.get(userId));
        }

        @Override
        public boolean verifyAccountSignature(UUID userId, String message, String signature) {
            checkedSignatures.add(signature);
            AccountRef account = accounts.get(userId);
            return account != null
                    && account.state() == AccountState.ACTIVE
                    && message.startsWith(VERIFICATION_FIRST_LINE + "\n")
                    && signature.equals("valid:" + message);
        }
    }

    /** The Access summary: a fixed snapshot for the person, or nothing. */
    static final class Summaries implements AccessSummarySource {

        int assembled;

        @Override
        public Optional<AccessSummary> assemble(UUID tenantId, UUID userId) {
            assembled++;
            return Optional.of(new AccessSummary(
                    "Abcd…wxyz",
                    "PHANTOM",
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Optional.empty(),
                    List.of(),
                    2,
                    5,
                    1,
                    List.of()));
        }
    }
}
