package com.memetrade.identity.domain.login;

import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.domain.invite.IdentityDirectory;
import com.memetrade.identity.domain.session.Account;
import com.memetrade.identity.domain.session.AccountRepository;
import com.memetrade.identity.domain.session.AuthenticatedSession;
import com.memetrade.identity.domain.session.OpenedSession;
import com.memetrade.identity.domain.session.RefreshOutcome;
import com.memetrade.identity.domain.session.Session;
import com.memetrade.identity.domain.session.SessionService;
import com.memetrade.identity.domain.signature.Ed25519SignatureVerifier;
import com.memetrade.shared.wallet.WalletAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * The administrator entry of U8 (BR1.1 to BR1.6, AC1.2.6; security-design.md "Entrada do administrador"). With
 * {@code AdminAuthorization}, the only production class that decides on the ADMIN role (ArchUnit).
 *
 * <ul>
 *   <li><b>Verify</b>, in one transaction: the challenge must be ISSUED, not expired and of the ADMINISTRATOR domain
 *       (consumed at once, so a refusal keeps it consumed); the message and the wallet must be the issued ones; the
 *       Ed25519 signature (native verifier of U1, no Solana SDK) must verify; the wallet must be in
 *       {@code ADMIN_WALLET_ADDRESSES} and must not belong to a guest. The first entry creates the AdminAccount (role
 *       ADMIN, system tenant). The session lasts 8 hours with access tokens of 15 minutes and one
 *       {@code ADMIN_LOGIN} is written. Every refusal writes exactly one {@code LOGIN_REJECTED} with the exact reason
 *       and still commits; the answer stays generic.
 *   <li><b>Refresh</b>: only an ADMIN session, never extended; a wallet removed from the list revokes the session and
 *       writes {@code ADMIN_SESSION_REVOKED} (BR1.4).
 *   <li><b>Logout</b>: revokes the session and writes {@code ADMIN_LOGOUT}; repeating is a no-op (BR1.6).
 * </ul>
 *
 * The login signature authorises nothing but this entry (BR1.2): it is never stored, logged or audited.
 */
public class AdminLoginService {

    public static final String ADMIN_LOGIN = "ADMIN_LOGIN";
    public static final String ADMIN_LOGOUT = "ADMIN_LOGOUT";
    public static final String ADMIN_SESSION_REVOKED = "ADMIN_SESSION_REVOKED";

    private final LoginChallengeRepository challenges;
    private final AccountRepository accounts;
    private final AdminAccounts adminAccounts;
    private final IdentityDirectory directory;
    private final SessionService sessions;
    private final Ed25519SignatureVerifier signatures;
    private final AuditEventPublisher audit;
    private final AdminWallets wallets;
    private final AdminLoginSettings settings;
    private final Clock clock;

    public AdminLoginService(
            LoginChallengeRepository challenges,
            AccountRepository accounts,
            AdminAccounts adminAccounts,
            IdentityDirectory directory,
            SessionService sessions,
            Ed25519SignatureVerifier signatures,
            AuditEventPublisher audit,
            AdminWallets wallets,
            AdminLoginSettings settings,
            Clock clock) {
        this.challenges = challenges;
        this.accounts = accounts;
        this.adminAccounts = adminAccounts;
        this.directory = directory;
        this.sessions = sessions;
        this.signatures = signatures;
        this.audit = audit;
        this.wallets = wallets;
        this.settings = settings;
        this.clock = clock;
    }

    /** Result of an administrator verification. */
    public sealed interface Outcome {

        record Accepted(OpenedSession session, String maskedWallet) implements Outcome {}

        record Rejected(AdminRejection reason) implements Outcome {}
    }

    @Transactional
    public Outcome verify(LoginVerificationService.VerifyCommand command) {
        Instant now = clock.instant();
        Optional<LoginChallenge> consumed = consume(command, now);
        if (consumed.isEmpty()) {
            return reject(command, AdminRejection.NONCE_INVALID, now);
        }
        LoginChallenge challenge = consumed.get();
        if (!settings.domain().equals(challenge.domain())
                || !challenge.message().equals(command.message())
                || !challenge.walletAddress().equals(command.walletAddress())) {
            return reject(command, AdminRejection.NONCE_INVALID, now);
        }
        if (signatures.verify(challenge.walletAddress(), challenge.message(), command.signature())
                != Ed25519SignatureVerifier.Result.VALID) {
            return reject(command, AdminRejection.SIGNATURE_INVALID, now);
        }
        if (!wallets.isListed(challenge.walletAddress())) {
            return reject(command, AdminRejection.NOT_ADMIN_WALLET, now);
        }
        Optional<Account> existing = accounts.findByWallet(challenge.walletAddress());
        if (existing.isPresent() && existing.get().role() != Role.ADMIN) {
            return reject(command, AdminRejection.WALLET_IS_GUEST, now);
        }
        if (existing.isPresent()
                && !(existing.get().userActive() && existing.get().tenantActive())) {
            return reject(command, AdminRejection.NOT_ADMIN_WALLET, now);
        }
        Account admin = existing.orElseGet(() -> createAccount(challenge, command, now));
        OpenedSession opened = sessions.openWithPolicy(admin, now, settings.sessionMaxAge(), policy());
        String masked = WalletAddress.mask(challenge.walletAddress());
        audit.publish(AuditEventCommand.admin(
                admin.userId(),
                ADMIN_LOGIN,
                "session:" + opened.sessionId(),
                command.correlationId(),
                now,
                Map.of("provider", command.provider(), "wallet", masked)));
        return new Outcome.Accepted(opened, masked);
    }

    /** BR1.3, BR1.4: rotation of the administrator cookie; never extends the 8 hours. */
    @Transactional
    public RefreshOutcome refresh(String refreshToken, String correlationId) {
        RefreshOutcome outcome = sessions.refresh(refreshToken, correlationId, policy());
        if (outcome instanceof RefreshOutcome.Refused refused
                && refused.reason() == RefreshOutcome.Reason.NOT_ALLOWED) {
            audit.publish(AuditEventCommand.system(
                    ADMIN_SESSION_REVOKED,
                    "session",
                    correlationId,
                    clock.instant(),
                    Map.of("reason", "NOT_ADMIN_WALLET")));
        }
        return outcome;
    }

    /** BR1.6: revokes the session and writes ADMIN_LOGOUT once; an already closed session is a silent no-op. */
    @Transactional
    public void logout(AuthenticatedSession session, String correlationId) {
        if (sessions.revoke(session.sessionId())) {
            audit.publish(AuditEventCommand.admin(
                    session.principal().userId(),
                    ADMIN_LOGOUT,
                    "session:" + session.sessionId(),
                    correlationId,
                    clock.instant(),
                    Map.of()));
        }
    }

    /** Q4 of the NFR Design: a refusal by the limits of the entry is recorded like the others, before the 429. */
    @Transactional
    public void recordRateLimited(String walletAddress, String correlationId) {
        reject(
                new LoginVerificationService.VerifyCommand(walletAddress, null, null, "NONE", correlationId),
                AdminRejection.RATE_LIMITED,
                clock.instant());
    }

    /** ADMIN sessions only, 15-minute access tokens, no sliding, and the wallet must still be listed (BR1.4). */
    private SessionService.RefreshPolicy policy() {
        return new SessionService.RefreshPolicy(
                Role.ADMIN, settings.accessTokenTtl(), Optional.empty(), this::stillListed);
    }

    private boolean stillListed(Session session) {
        return directory.walletOf(session.userId()).map(wallets::isListed).orElse(false);
    }

    private Optional<LoginChallenge> consume(LoginVerificationService.VerifyCommand command, Instant now) {
        Optional<String> nonce = LoginMessage.parse(command.message()).map(LoginMessage::nonce);
        Optional<LoginChallenge> found = nonce.flatMap(challenges::findByNonce);
        if (found.isEmpty() || found.get().state() != ChallengeState.ISSUED) {
            return Optional.empty();
        }
        if (found.get().isExpiredAt(now)) {
            challenges.expire(found.get().nonce());
            return Optional.empty();
        }
        return challenges.consume(found.get().nonce(), now) ? found : Optional.empty();
    }

    private Account createAccount(
            LoginChallenge challenge, LoginVerificationService.VerifyCommand command, Instant now) {
        UUID adminId = UUID.randomUUID();
        adminAccounts.createAdmin(adminId, settings.systemTenant(), challenge.walletAddress(), command.provider(), now);
        return new Account(adminId, settings.systemTenant(), Role.ADMIN, true, true);
    }

    private Outcome reject(LoginVerificationService.VerifyCommand command, AdminRejection reason, Instant now) {
        boolean validAddress = WalletAddress.isValid(command.walletAddress());
        Map<String, String> details = new HashMap<>();
        details.put("reason", reason.name());
        details.put("entry", "ADMIN");
        if (validAddress) {
            details.put("walletAddress", command.walletAddress());
        }
        audit.publish(AuditEventCommand.anonymous(
                com.memetrade.audit.api.AuditActions.LOGIN_REJECTED,
                validAddress ? WalletAddress.mask(command.walletAddress()) : "anonymous",
                null,
                command.correlationId(),
                now,
                details));
        return new Outcome.Rejected(reason);
    }
}
