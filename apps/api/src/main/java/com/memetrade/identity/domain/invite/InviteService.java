package com.memetrade.identity.domain.invite;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.identity.api.IdentityCounts;
import com.memetrade.identity.api.InvitePage;
import com.memetrade.identity.api.InviteStatus;
import com.memetrade.identity.api.InviteView;
import com.memetrade.identity.api.IssuedInvite;
import com.memetrade.identity.api.TenantOption;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.FieldViolation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issuance, list, revocation and counts of invitations (F1, F2, F3, F9; BR1.1 to BR1.8). Every instant comes from the
 * injected clock, truncated to the microsecond the database keeps. Issuance and revocation run inside the caller's
 * transaction (propagation MANDATORY) so the invitation, its audit event and the idempotency record commit together
 * (NFR4.5, NFR6.3).
 */
public class InviteService {

    public static final Duration MIN_VALIDITY = Duration.ofHours(1);
    public static final Duration MAX_VALIDITY = Duration.ofDays(30);
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private final InviteRepository invites;
    private final IdentityDirectory directory;
    private final InviteCodes codes;
    private final AuditEventPublisher audit;
    private final Clock clock;

    public InviteService(
            InviteRepository invites,
            IdentityDirectory directory,
            InviteCodes codes,
            AuditEventPublisher audit,
            Clock clock) {
        this.invites = invites;
        this.directory = directory;
        this.codes = codes;
        this.audit = audit;
        this.clock = clock;
    }

    /** F1: validates tenant and validity, stores only digest and suffix, records INVITE_ISSUED (never the code). */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedInvite issue(UUID tenantId, Instant expiresAt, AdminActor admin) {
        Instant now = now();
        requireActiveTenant(tenantId);
        Instant validUntil = requireValidity(expiresAt, now);
        String code = codes.newCode();
        Invite invite = new Invite(
                UUID.randomUUID(),
                tenantId,
                InviteCodes.digest(code),
                InviteCodes.suffix(code),
                InviteStatus.PENDING,
                admin.issuedBy(),
                now,
                validUntil,
                null,
                null,
                null);
        invites.insert(invite);
        audit.publish(AuditEventCommand.admin(
                admin.adminId(),
                AuditActions.INVITE_ISSUED,
                target(invite.inviteId()),
                admin.correlationId(),
                now,
                Map.of("tenantId", tenantId.toString(), "expiresAt", validUntil.toString())));
        return new IssuedInvite(invite.viewAt(now), code);
    }

    /**
     * F3: locks the row; PENDING becomes REVOKED with INVITE_REVOKED; REVOKED is answered as it is without an event;
     * USED or EXPIRED is {@code 409 INVITE_NOT_REVOCABLE}; unknown is {@code 404}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public InviteView revoke(UUID inviteId, AdminActor admin) {
        Instant now = now();
        Invite invite = invites.findByIdForUpdate(inviteId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Invitation not found."));
        InviteStatus status = invite.statusAt(now);
        if (status == InviteStatus.REVOKED) {
            return invite.viewAt(now);
        }
        if (status != InviteStatus.PENDING) {
            throw new ApiException(
                    ErrorCode.INVITE_NOT_REVOCABLE,
                    "Only a pending invitation can be revoked.",
                    List.of(),
                    status.name(),
                    Map.of());
        }
        if (!invites.markRevoked(inviteId, now)) {
            throw new IllegalStateException("The locked pending invitation could not be revoked");
        }
        audit.publish(AuditEventCommand.admin(
                admin.adminId(),
                AuditActions.INVITE_REVOKED,
                target(inviteId),
                admin.correlationId(),
                now,
                Map.of("tenantId", invite.tenantId().toString())));
        return new Invite(
                        invite.inviteId(),
                        invite.tenantId(),
                        invite.codeDigest(),
                        invite.codeSuffix(),
                        InviteStatus.REVOKED,
                        invite.issuedBy(),
                        invite.createdAt(),
                        invite.expiresAt(),
                        null,
                        null,
                        now)
                .viewAt(now);
    }

    /** F2: one page by createdAt descending, every item derived with the same instant (BR1.3). */
    @Transactional(readOnly = true)
    public InvitePage list(Optional<String> cursor, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw ApiException.validation("limit", "Must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        Optional<InviteCursor> after = cursor.filter(value -> !value.isBlank()).map(InviteCursor::decode);
        Instant now = now();
        List<Invite> rows = invites.page(after, limit + 1);
        boolean more = rows.size() > limit;
        List<Invite> page = more ? rows.subList(0, limit) : rows;
        String next = more ? InviteCursor.after(page.getLast()).encode() : null;
        return new InvitePage(page.stream().map(invite -> invite.viewAt(now)).toList(), next);
    }

    /** F9: numbers only; the four invitation keys are always present (BR1.6). */
    @Transactional(readOnly = true)
    public IdentityCounts counts() {
        return new IdentityCounts(directory.countActiveGuests(), invites.countByStatusAt(now()));
    }

    @Transactional(readOnly = true)
    public List<TenantOption> activeTenants() {
        return directory.activeTenants();
    }

    private void requireActiveTenant(UUID tenantId) {
        boolean active = tenantId != null
                && directory
                        .tenant(tenantId)
                        .map(IdentityDirectory.TenantState::active)
                        .orElse(false);
        if (!active) {
            throw unprocessable("tenantId", "Must be an existing ACTIVE tenant.");
        }
    }

    /** BR1.1: now + 1 hour <= expiresAt <= now + 30 days, both limits included. */
    private static Instant requireValidity(Instant expiresAt, Instant now) {
        if (expiresAt == null) {
            throw unprocessable("expiresAt", "Is required.");
        }
        Instant validUntil = expiresAt.truncatedTo(ChronoUnit.MICROS);
        if (validUntil.isBefore(now.plus(MIN_VALIDITY)) || validUntil.isAfter(now.plus(MAX_VALIDITY))) {
            throw unprocessable("expiresAt", "Must be between 1 hour and 30 days from now.");
        }
        return validUntil;
    }

    private static ApiException unprocessable(String field, String message) {
        return new ApiException(
                ErrorCode.UNPROCESSABLE_FIELDS,
                "One or more fields are invalid.",
                List.of(new FieldViolation(field, message)),
                null,
                Map.of());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String target(UUID inviteId) {
        return "invite:" + inviteId;
    }

    /**
     * The administrator acting: its id (audit actor), the masked wallet stored as {@code issuedBy} and the request
     * correlation id.
     */
    public record AdminActor(UUID adminId, String issuedBy, String correlationId) {

        public AdminActor {
            Objects.requireNonNull(adminId, "adminId");
            Objects.requireNonNull(issuedBy, "issuedBy");
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }
}
