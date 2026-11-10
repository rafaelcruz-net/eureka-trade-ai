package com.memetrade.privacy.domain.request;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence of the requests ({@code data_subject_request}, migration 0014). Every read of a person filters by the
 * tenant AND the person (BR4.4, NFR2.1); the administrative reads go by id. Runs inside the transaction of the caller.
 */
public interface DataRequestStore {

    /**
     * Inserts a new request.
     *
     * @throws OpenRequestConflictException when the person already has an open request of the type (the unique index
     *     {@code uq_open_request_per_type}, BR4.2, also under a race)
     */
    void insert(DataSubjectRequest request);

    /** Whether the person has an open request (RECEIVED, AWAITING_VERIFICATION or IN_PROGRESS) of the type. */
    boolean existsOpen(UUID tenantId, UUID userId, DataRequestType type);

    /** The request when it belongs to that person of that tenant; empty otherwise (BR4.4). */
    Optional<DataSubjectRequest> findOwned(UUID tenantId, UUID userId, UUID requestId);

    /** Every request of the person, newest first. */
    List<DataSubjectRequest> listOwned(UUID tenantId, UUID userId);

    /** Administrative read by id. */
    Optional<DataSubjectRequest> findById(UUID requestId);

    /** Administrative read by id, locking the row until the end of the transaction (transitions are serialised). */
    Optional<DataSubjectRequest> lockById(UUID requestId);

    /** Writes the mutable fields of a request read with {@link #lockById(UUID)}. */
    void update(DataSubjectRequest request);

    /** The administrator list (NFR3.2): by due date then id, optionally of one status, after a key. */
    List<DataSubjectRequest> page(Optional<DataRequestStatus> status, Optional<PageKey> after, int limit);

    /**
     * BR4.13, NFR3.4: expires at most {@code limit} PENDING verifications whose validity ended by {@code now} and
     * refuses their AWAITING_VERIFICATION requests with {@code reason}, conditionally on both states, in the caller's
     * transaction.
     *
     * @return the refused requests
     */
    List<DataSubjectRequest> refuseExpiredVerifications(Instant now, String reason, int limit);

    /**
     * BR4.14, NFR3.4: marks {@code overdueNotifiedAt} on at most {@code limit} open requests due by {@code now} and
     * never notified, conditionally on that, in the caller's transaction.
     *
     * @return the requests marked now
     */
    List<DataSubjectRequest> markOverdue(Instant now, int limit);

    /** The position of a page of the administrator list. */
    record PageKey(Instant dueAt, UUID requestId) {
        public PageKey {
            Objects.requireNonNull(dueAt, "dueAt");
            Objects.requireNonNull(requestId, "requestId");
        }
    }
}
