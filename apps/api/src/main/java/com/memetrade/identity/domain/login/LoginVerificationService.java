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

    public LoginVerificationService(
            LoginChallengeRepository challenges,
            AccountRepository accounts,
            SessionService sessions,
            Ed25519SignatureVerifier signatures,
            AuditEventPublisher audit,
            LoginSettings settings,
            Clock clock) {
        this.challenges = challenges;
        this.accounts = accounts;
        this.sessions = sessions;
        this.signatures = signatures;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
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
        if (!challenge.message().equals(command.message())
                || !challenge.walletAddress().equals(command.walletAddress())) {
            return reject(command, RejectionReason.MESSAGE_MISMATCH, now);
        }
        Ed25519SignatureVerifier.Result signature =
                signatures.verify(challenge.walletAddress(), challenge.message(), command.signature());
        if (signature != Ed25519SignatureVerifier.Result.VALID) {
            return reject(command, RejectionReason.WRONG_SIGNER, now);
        }
        if (settings.adminWalletAddresses().contains(challenge.walletAddress())) {
            return reject(command, RejectionReason.ADMIN_WALLET, now);
        }
        Optional<Account> account =
                accounts.findByWallet(challenge.walletAddress()).filter(Account::canEnterAsGuest);
        if (account.isEmpty()) {
            return reject(command, RejectionReason.UNKNOWN_ACCOUNT, now);
        }
        OpenedSession opened = sessions.open(account.get(), now);
        audit.publish(AuditEventCommand.person(
                account.get().tenantId(),
                account.get().userId(),
                AuditActions.LOGIN_SUCCEEDED,
                "session:" + opened.sessionId(),
                command.correlationId(),
                now,
                Map.of("provider", command.provider(), "walletAddress", challenge.walletAddress())));
        return new VerificationOutcome.Accepted(opened);
    }

    private VerificationOutcome reject(VerifyCommand command, RejectionReason reason, Instant now) {
        boolean validAddress = WalletAddress.isValid(command.walletAddress());
        Map<String, String> details = new HashMap<>();
        details.put("reason", reason.name());
        if (validAddress) {
            details.put("walletAddress", command.walletAddress());
        }
        audit.publish(AuditEventCommand.anonymous(
                AuditActions.LOGIN_REJECTED,
                validAddress ? WalletAddress.mask(command.walletAddress()) : "anonymous",
                null,
                command.correlationId(),
                now,
                details));
        return new VerificationOutcome.Rejected(reason);
    }

    /** Verification input (exactly the fields of C1 accepted in U1) plus the request correlation id. */
    public record VerifyCommand(
            String walletAddress, String message, String signature, String provider, String correlationId) {

        @Override
        public String toString() {
            // Message (nonce) and signature never reach logs (BR4.3).
            return "VerifyCommand[provider=" + provider + "]";
        }
    }
}
