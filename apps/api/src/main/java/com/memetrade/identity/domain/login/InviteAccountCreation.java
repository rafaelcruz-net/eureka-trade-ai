package com.memetrade.identity.domain.login;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.identity.api.InviteStatus;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.domain.account.GuestAccountRepository;
import com.memetrade.identity.domain.account.GuestAccountRepository.TermsAcceptance;
import com.memetrade.identity.domain.invite.IdentityDirectory;
import com.memetrade.identity.domain.invite.Invite;
import com.memetrade.identity.domain.invite.InviteCodes;
import com.memetrade.identity.domain.invite.InviteRepository;
import com.memetrade.identity.domain.session.Account;
import com.memetrade.identity.domain.session.AccountRepository;
import com.memetrade.identity.domain.session.OpenedSession;
import com.memetrade.identity.domain.session.SessionService;
import com.memetrade.identity.domain.terms.CurrentTerms;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * F5: creation of the account in the verification with an invitation, INSIDE the verification transaction of U1,
 * after the signature was accepted and the challenge CONSUMED (BR3.6). The invitation row is locked before its state
 * is read (BR3.4, AC7.1.3); the checks run in the order of BR3.2 and stop at the first refusal; on success person,
 * wallet, used invitation, acceptance, ACCOUNT_CREATED and session are written together (BR3.3). The tenant always
 * comes from the invitation, never from the request (NFR5.1).
 */
public class InviteAccountCreation {

    private final InviteRepository invites;
    private final IdentityDirectory directory;
    private final AccountRepository accounts;
    private final GuestAccountRepository guests;
    private final CurrentTerms terms;
    private final SessionService sessions;
    private final AuditEventPublisher audit;
    private final LoginSettings settings;

    public InviteAccountCreation(
            InviteRepository invites,
            IdentityDirectory directory,
            AccountRepository accounts,
            GuestAccountRepository guests,
            CurrentTerms terms,
            SessionService sessions,
            AuditEventPublisher audit,
            LoginSettings settings) {
        this.invites = invites;
        this.directory = directory;
        this.accounts = accounts;
        this.guests = guests;
        this.terms = terms;
        this.sessions = sessions;
        this.audit = audit;
        this.settings = settings;
    }

    /** Outcome of the creation: the opened session, or the reason of the first failed check of BR3.2. */
    public sealed interface Result {

        record Created(OpenedSession session) implements Result {}

        record Refused(RejectionReason reason) implements Result {}
    }

    /** Request data of the creation; the wallet is the one of the consumed challenge. */
    public record Request(
            String walletAddress,
            String provider,
            String inviteCode,
            String acceptedTermsVersion,
            String correlationId) {

        @Override
        public String toString() {
            // The invitation code never reaches a log line (BR2.3).
            return "Request[provider=" + provider + "]";
        }
    }

    /**
     * Checks in the order of BR3.2 and creates the account.
     *
     * @throws com.memetrade.identity.domain.account.WalletAlreadyLinkedException when a concurrent creation linked
     *     the same wallet first (the caller rolls back and records the refusal in a new transaction, BR3.7)
     */
    public Result create(Request request, Instant now) {
        Optional<Invite> locked = InviteCodes.isWellFormed(request.inviteCode())
                ? invites.findByDigestForUpdate(InviteCodes.digest(request.inviteCode()))
                : Optional.empty();
        if (locked.isEmpty()) {
            return refused(RejectionReason.INVITE_NOT_FOUND);
        }
        Invite invite = locked.get();
        Optional<RejectionReason> refusal = firstRefusal(invite, request, now);
        if (refusal.isPresent()) {
            return refused(refusal.get());
        }
        return new Result.Created(createAccount(invite, request, now));
    }

    /** BR3.2 checks 2 to 6; the invitation (check 1) was found and is locked. */
    private Optional<RejectionReason> firstRefusal(Invite invite, Request request, Instant now) {
        InviteStatus status = invite.statusAt(now);
        if (status != InviteStatus.PENDING) {
            return Optional.of(
                    switch (status) {
                        case EXPIRED -> RejectionReason.INVITE_EXPIRED;
                        case REVOKED -> RejectionReason.INVITE_REVOKED;
                        default -> RejectionReason.INVITE_USED;
                    });
        }
        if (settings.adminWalletAddresses().contains(request.walletAddress())) {
            return Optional.of(RejectionReason.ADMIN_WALLET);
        }
        if (accounts.findByWallet(request.walletAddress()).isPresent()) {
            return Optional.of(RejectionReason.WALLET_ALREADY_LINKED);
        }
        boolean tenantActive = directory
                .tenant(invite.tenantId())
                .map(IdentityDirectory.TenantState::active)
                .orElse(false);
        if (!tenantActive) {
            return Optional.of(RejectionReason.TENANT_INACTIVE);
        }
        if (!terms.current().version().equals(request.acceptedTermsVersion())) {
            return Optional.of(RejectionReason.TERMS_NOT_ACCEPTED);
        }
        return Optional.empty();
    }

    private OpenedSession createAccount(Invite invite, Request request, Instant now) {
        UUID userId = UUID.randomUUID();
        String version = terms.current().version();
        guests.createGuest(userId, invite.tenantId(), request.walletAddress(), request.provider(), now);
        if (!invites.markUsed(invite.inviteId(), userId, now)) {
            throw new IllegalStateException("The locked pending invitation could not be marked USED");
        }
        guests.recordAcceptance(
                new TermsAcceptance(UUID.randomUUID(), userId, invite.tenantId(), version, now, invite.inviteId()));
        audit.publish(AuditEventCommand.person(
                invite.tenantId(),
                userId,
                AuditActions.ACCOUNT_CREATED,
                "invite:" + invite.inviteId(),
                request.correlationId(),
                now,
                Map.of(
                        "termsVersion", version,
                        "acceptedAt", now.toString(),
                        "provider", request.provider(),
                        "walletAddress", request.walletAddress())));
        return sessions.open(new Account(userId, invite.tenantId(), Role.BETA_USER, true, true), now);
    }

    private static Result refused(RejectionReason reason) {
        return new Result.Refused(reason);
    }
}
