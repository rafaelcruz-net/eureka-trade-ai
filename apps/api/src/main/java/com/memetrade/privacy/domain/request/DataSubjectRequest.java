package com.memetrade.privacy.domain.request;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A data subject request (entities.md DataSubjectRequest; functional-spec.md "Estados"). Immutable: every change of
 * state returns a new value, and the service checks the transition table (BR4.5) before asking for one. {@code details},
 * {@code contact}, {@code response} and {@code refusalReason} never reach {@link #toString()}, a log, an event or a
 * metric (BR4.3).
 *
 * @param tenantId the tenant of the person (ACCOUNT) or of the linked account (EMAIL); empty for an EMAIL request
 *     without an account
 * @param userId the person (ACCOUNT, always) or the linked account (EMAIL, optional)
 * @param contact the e-mail of an EMAIL request; empty for ACCOUNT
 * @param dueAt {@code receivedAt} plus 15 days (BR4.1)
 * @param closedAt set exactly in DONE and REFUSED
 * @param accessSummary the snapshot released with a DONE ACCESS request (BR4.8)
 * @param overdueNotifiedAt when the single overdue notice was published (BR4.14)
 * @param replySentAt when the reply was sent to the contact of a closed EMAIL request (BR4.17)
 */
public record DataSubjectRequest(
        UUID requestId,
        Optional<UUID> tenantId,
        Optional<UUID> userId,
        DataRequestType type,
        DataRequestOrigin origin,
        String details,
        Optional<String> contact,
        DataRequestStatus status,
        Instant receivedAt,
        Instant dueAt,
        Optional<String> response,
        Optional<String> refusalReason,
        Optional<Instant> closedAt,
        boolean identityVerified,
        Optional<AccessSummary> accessSummary,
        Optional<Instant> overdueNotifiedAt,
        Optional<Instant> replySentAt) {

    public DataSubjectRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(details, "details");
        Objects.requireNonNull(contact, "contact");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(dueAt, "dueAt");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(refusalReason, "refusalReason");
        Objects.requireNonNull(closedAt, "closedAt");
        Objects.requireNonNull(accessSummary, "accessSummary");
        Objects.requireNonNull(overdueNotifiedAt, "overdueNotifiedAt");
        Objects.requireNonNull(replySentAt, "replySentAt");
        if (origin == DataRequestOrigin.ACCOUNT && (tenantId.isEmpty() || userId.isEmpty() || contact.isPresent())) {
            throw new IllegalArgumentException("An ACCOUNT request has its tenant and person and no contact");
        }
        if (origin == DataRequestOrigin.EMAIL && (contact.isEmpty() || tenantId.isPresent() != userId.isPresent())) {
            throw new IllegalArgumentException("An EMAIL request has a contact, and a tenant exactly with an account");
        }
        if (status.isClosed() != closedAt.isPresent()) {
            throw new IllegalArgumentException("A request is closed exactly in DONE and REFUSED");
        }
    }

    /** BR4.1: a request of the authenticated person, RECEIVED and due {@code responseTime} later. */
    public static DataSubjectRequest fromAccount(
            UUID requestId,
            UUID tenantId,
            UUID userId,
            DataRequestType type,
            String details,
            Instant receivedAt,
            Duration responseTime) {
        return new DataSubjectRequest(
                requestId,
                Optional.of(tenantId),
                Optional.of(userId),
                type,
                DataRequestOrigin.ACCOUNT,
                details,
                Optional.empty(),
                DataRequestStatus.RECEIVED,
                receivedAt,
                receivedAt.plus(responseTime),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /** BR4.10: a request registered from the e-mail of the DPO, with the linked account when there is one. */
    public static DataSubjectRequest fromEmail(
            UUID requestId,
            Optional<LinkedAccount> account,
            DataRequestType type,
            String details,
            String contact,
            Instant receivedAt,
            Duration responseTime) {
        return new DataSubjectRequest(
                requestId,
                account.map(LinkedAccount::tenantId),
                account.map(LinkedAccount::userId),
                type,
                DataRequestOrigin.EMAIL,
                details,
                Optional.of(contact),
                DataRequestStatus.RECEIVED,
                receivedAt,
                receivedAt.plus(responseTime),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /** The account an EMAIL request is linked to. */
    public record LinkedAccount(UUID tenantId, UUID userId) {
        public LinkedAccount {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(userId, "userId");
        }
    }

    public DataSubjectRequest inProgress(boolean verified) {
        return with(
                DataRequestStatus.IN_PROGRESS,
                response,
                refusalReason,
                closedAt,
                identityVerified || verified,
                accessSummary,
                overdueNotifiedAt,
                replySentAt);
    }

    public DataSubjectRequest awaitingVerification() {
        return with(
                DataRequestStatus.AWAITING_VERIFICATION,
                response,
                refusalReason,
                closedAt,
                identityVerified,
                accessSummary,
                overdueNotifiedAt,
                replySentAt);
    }

    public DataSubjectRequest done(Optional<String> answer, Optional<AccessSummary> summary, Instant at) {
        return with(
                DataRequestStatus.DONE,
                answer,
                Optional.empty(),
                Optional.of(at),
                identityVerified,
                summary,
                overdueNotifiedAt,
                replySentAt);
    }

    public DataSubjectRequest refused(String reason, Instant at) {
        return with(
                DataRequestStatus.REFUSED,
                response,
                Optional.of(reason),
                Optional.of(at),
                identityVerified,
                Optional.empty(),
                overdueNotifiedAt,
                replySentAt);
    }

    public DataSubjectRequest overdueNotified(Instant at) {
        return with(
                status,
                response,
                refusalReason,
                closedAt,
                identityVerified,
                accessSummary,
                Optional.of(at),
                replySentAt);
    }

    public DataSubjectRequest replySent(Instant at) {
        return with(
                status,
                response,
                refusalReason,
                closedAt,
                identityVerified,
                accessSummary,
                overdueNotifiedAt,
                Optional.of(at));
    }

    /** BR4.4: the person sees the response, the reason and the summary only once the request is closed. */
    public DataSubjectRequest forPerson() {
        if (status.isClosed()) {
            return this;
        }
        return with(
                status,
                Optional.empty(),
                Optional.empty(),
                closedAt,
                identityVerified,
                Optional.empty(),
                overdueNotifiedAt,
                replySentAt);
    }

    /** BR4.14: open and past its due date. */
    public boolean isOverdue(Instant now) {
        return status.isOpen() && !dueAt.isAfter(now);
    }

    private DataSubjectRequest with(
            DataRequestStatus newStatus,
            Optional<String> newResponse,
            Optional<String> newReason,
            Optional<Instant> newClosedAt,
            boolean verified,
            Optional<AccessSummary> summary,
            Optional<Instant> notifiedAt,
            Optional<Instant> sentAt) {
        return new DataSubjectRequest(
                requestId,
                tenantId,
                userId,
                type,
                origin,
                details,
                contact,
                newStatus,
                receivedAt,
                dueAt,
                newResponse,
                newReason,
                newClosedAt,
                verified,
                summary,
                notifiedAt,
                sentAt);
    }

    /** Never the text, the contact, the response or the reason (BR4.3). */
    @Override
    public String toString() {
        return "DataSubjectRequest[requestId=" + requestId + ", type=" + type + ", origin=" + origin + ", status="
                + status + "]";
    }
}
