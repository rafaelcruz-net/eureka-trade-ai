package com.memetrade.identity.domain.login;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.identity.domain.session.Account;
import com.memetrade.identity.domain.session.AccountRepository;
import com.memetrade.identity.domain.session.OpenedSession;
import com.memetrade.identity.domain.session.SessionService;
import com.memetrade.identity.domain.signature.Ed25519SignatureVerifier;
import com.memetrade.shared.wallet.WalletAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login verification in a single transaction (F1 step 6). Order: parse the nonce line, find the challenge, check
 * state and deadline, consume the nonce with a conditional update, compare the exact message and wallet, verify the
 * Ed25519 signature, check the administrator list and the account, then open the session. Every refusal writes the
 * new challenge state and exactly one LOGIN_REJECTED event and still commits (BR1.6). Nothing is stored for replay
 * (BR1.10).
 */
public class LoginVerificationService {

    private final LoginChallengeRepository challenges;
    private final AccountRepository accounts;
    private final SessionService sessions;
    private final Ed25519SignatureVerifier signatures;
    private final AuditEventPublisher audit;
    private final LoginSettings settings;
    private final Clock clock;
    private final InviteAccountCreation invitations;

    /** U1 wiring (no invitation path): a verification with an invitation code is refused as unknown invitation. */
    public LoginVerificationService(
            LoginChallengeRepository challenges,
            AccountRepository accounts,
            SessionService sessions,
            Ed25519SignatureVerifier signatures,
            AuditEventPublisher audit,
            LoginSettings settings,
            Clock clock) {
        this(challenges, accounts, sessions, signatures, audit, settings, clock, null);
    }

    public LoginVerificationService(
            LoginChallengeRepository challenges,
            AccountRepository accounts,
            SessionService sessions,
            Ed25519SignatureVerifier signatures,
            AuditEventPublisher audit,
            LoginSettings settings,
            Clock clock,
            InviteAccountCreation invitations) {
        this.challenges = challenges;
        this.accounts = accounts;
        this.sessions = sessions;
        this.signatures = signatures;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
        this.invitations = invitations;
    }

    @Transactional
    public VerificationOutcome verify(VerifyCommand command) {
        Instant now = clock.instant();
        Optional<LoginMessage> parsed = LoginMessage.parse(command.message());
        if (parsed.isEmpty()) {
            return reject(command, RejectionReason.MALFORMED_MESSAGE, now);
        }
        Optional<LoginChallenge> found = challenges.findByNonce(parsed.get().nonce());
        if (found.isEmpty()) {
            return reject(command, RejectionReason.UNKNOWN_CHALLENGE, now);
        }
        LoginChallenge challenge = found.get();
        if (challenge.state() != ChallengeState.ISSUED) {
            return reject(command, RejectionReason.NONCE_ALREADY_USED, now);
        }
        if (challenge.isExpiredAt(now)) {
            challenges.expire(challenge.nonce());
            return reject(command, RejectionReason.EXPIRED, now);
        }
        if (!challenges.consume(challenge.nonce(), now)) {
            return reject(command, RejectionReason.NONCE_ALREADY_USED, now);
        }
        return verifyConsumed(command, challenge, now);
    }

    /** From here on the nonce is CONSUMED: any refusal keeps it consumed (BR1.4, BR1.5). */
    private VerificationOutcome verifyConsumed(VerifyCommand command, LoginChallenge challenge, Instant now) {
        if (!settings.domain().equals(challenge.domain())) {
            // U8: a challenge of the administrator entry (another domain) never opens a guest session.
            return reject(command, RejectionReason.UNKNOWN_CHALLENGE, now);
        }
        if (!challenge.message().equals(command.message())
                || !challenge.walletAddress().equals(command.walletAddress())) {
            return reject(command, RejectionReason.MESSAGE_MISMATCH, now);
        }
        Ed25519SignatureVerifier.Result signature =
                signatures.verify(challenge.walletAddress(), challenge.message(), command.signature());
        if (signature != Ed25519SignatureVerifier.Result.VALID) {
            return reject(command, RejectionReason.WRONG_SIGNER, now);
        }
        if (command.inviteCode() != null) {
            return createWithInvitation(command, challenge, now);
        }
        // BR3.8: without an invitation the U1 rules apply unchanged; acceptedTermsVersion alone is ignored.
        if (settings.adminWalletAddresses().contains(challenge.walletAddress())) {
            return reject(command, RejectionReason.ADMIN_WALLET, now);
        }
        Optional<Account> account =
                accounts.findByWallet(challenge.walletAddress()).filter(Account::canEnterAsGuest);
        if (account.isEmpty()) {
            return reject(command, RejectionReason.UNKNOWN_ACCOUNT, now);
        }
        // u5-anonymization (BR5.6, NFR5.6): the account is read again under a shared lock before the session exists, so
        // an anonymization either finished before (refused like an unknown account) or waits and revokes this session.
        if (!accounts.lockActiveForSession(account.get().userId())) {
            return reject(command, RejectionReason.UNKNOWN_ACCOUNT, now);
        }
        OpenedSession opened = sessions.open(account.get(), now);
        // u2-retention (BR2.1): the wallet goes by its own field; the trail keeps only its HMAC.
        audit.publish(AuditEventCommand.person(
                        account.get().tenantId(),
                        account.get().userId(),
                        AuditActions.LOGIN_SUCCEEDED,
                        "session:" + opened.sessionId(),
                        command.correlationId(),
                        now,
                        Map.of("provider", command.provider()))
                .withWallet(challenge.walletAddress()));
        return new VerificationOutcome.Accepted(opened);
    }

    /** U3 F5: the challenge is already CONSUMED; refusals keep it consumed and commit one LOGIN_REJECTED (BR3.6). */
    private VerificationOutcome createWithInvitation(VerifyCommand command, LoginChallenge challenge, Instant now) {
        if (invitations == null) {
            return reject(command, RejectionReason.INVITE_NOT_FOUND, now);
        }
        InviteAccountCreation.Result result = invitations.create(
                new InviteAccountCreation.Request(
                        challenge.walletAddress(),
                        command.provider(),
                        command.inviteCode(),
                        command.acceptedTermsVersion(),
                        command.correlationId()),
                now);
        return switch (result) {
            case InviteAccountCreation.Result.Created created -> new VerificationOutcome.Accepted(created.session());
            case InviteAccountCreation.Result.Refused refused -> reject(command, refused.reason(), now);
        };
    }

    /**
     * BR3.7 / NFR6.9: the verification transaction was rolled back by a unique violation of the wallet (a concurrent
     * creation won). In a NEW transaction the challenge becomes CONSUMED (when it is still ISSUED) and exactly one
     * LOGIN_REJECTED is written before the answer, so the signature never becomes valid again.
     */
    @Transactional
    public VerificationOutcome rejectAfterRollback(VerifyCommand command, RejectionReason reason) {
        Instant now = clock.instant();
        LoginMessage.parse(command.message())
                .flatMap(parsed -> challenges.findByNonce(parsed.nonce()))
                .filter(challenge -> challenge.state() == ChallengeState.ISSUED)
                .ifPresent(challenge -> {
                    if (!challenges.consume(challenge.nonce(), now)) {
                        challenges.expire(challenge.nonce());
                    }
                });
        return reject(command, reason, now);
    }

    private VerificationOutcome reject(VerifyCommand command, RejectionReason reason, Instant now) {
        audit.publish(
                rejectionEvent(command.walletAddress(), command.correlationId(), now, Map.of("reason", reason.name())));
        return new VerificationOutcome.Rejected(reason);
    }

    /**
     * u2-retention (BR2.1, BR2.15): the LOGIN_REJECTED of a guest or of an administrator. A valid wallet goes by its own
     * field (masked actor, HMAC in the trail); any other text is never recorded: actor {@code anonymous}, no HMAC and
     * {@code addressFormat=INVALID}.
     */
    static AuditEventCommand rejectionEvent(
            String walletAddress, String correlationId, Instant now, Map<String, String> reasonDetails) {
        Map<String, String> details = new HashMap<>(reasonDetails);
        if (WalletAddress.isValid(walletAddress)) {
            return AuditEventCommand.anonymousWallet(
                    AuditActions.LOGIN_REJECTED, walletAddress, null, correlationId, now, details);
        }
        details.put("addressFormat", "INVALID");
        return AuditEventCommand.anonymous(AuditActions.LOGIN_REJECTED, "anonymous", null, correlationId, now, details);
    }

    /**
     * Verification input (the fields of C1) plus the request correlation id. {@code inviteCode} and
     * {@code acceptedTermsVersion} arrive with U3 and are {@code null} in a sign-in without invitation.
     */
    public record VerifyCommand(
            String walletAddress,
            String message,
            String signature,
            String provider,
            String correlationId,
            String inviteCode,
            String acceptedTermsVersion) {

        /** U1 sign-in without invitation. */
        public VerifyCommand(
                String walletAddress, String message, String signature, String provider, String correlationId) {
            this(walletAddress, message, signature, provider, correlationId, null, null);
        }

        @Override
        public String toString() {
            // Message (nonce), signature and invitation code never reach logs (BR4.3, BR2.3).
            return "VerifyCommand[provider=" + provider + ", invite=" + (inviteCode != null) + "]";
        }
    }
}
