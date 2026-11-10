package com.memetrade.identity.domain.anonymization;

import com.memetrade.identity.api.AccountAnonymizationPort;
import com.memetrade.identity.domain.anonymization.AccountAnonymizationRepository.LockedAccount;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * J1 of u5-anonymization (BR5.2, BR5.5, BR5.6; reliability-design.md NFR5.6). {@link #anonymizeAccount} runs only in the
 * transaction of Privacy and keeps one order of statements:
 *
 * <ol>
 *   <li>the sessions of the person, locked by {@code session_id} (the refresh locks its session first too);
 *   <li>the address of the wallet, read without a lock, and its login challenges deleted (with the stored answers of
 *       their issuance). A login in flight holds its consumed challenge, so the anonymization waits here for it to
 *       finish BEFORE taking the account; the login itself takes the account only with a shared lock, so there is no
 *       cycle;
 *   <li>the account, locked exclusively; an account already ANONYMIZED answers its instant and changes nothing;
 *   <li>every ACTIVE session revoked AFTER the account lock, so in READ COMMITTED it also sees a session that a login
 *       committed just before;
 *   <li>the address and the provider removed with {@code anonymized_at}, and the status ANONYMIZED.
 * </ol>
 */
public class AccountAnonymizationService implements AccountAnonymizationPort {

    private static final String ANONYMIZED = "ANONYMIZED";

    private final AccountAnonymizationRepository accounts;

    public AccountAnonymizationService(AccountAnonymizationRepository accounts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AnonymizationTarget> target(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        return accounts.findTarget(userId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AccountAnonymization anonymizeAccount(UUID tenantId, UUID userId, Instant now) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(now, "now");
        accounts.lockSessions(tenantId, userId);
        Optional<String> address = accounts.walletOf(tenantId, userId);
        address.ifPresent(accounts::deleteLoginChallenges);
        LockedAccount account = accounts.lockAccount(tenantId, userId)
                .orElseThrow(() -> new IllegalArgumentException("No account " + userId + " in the tenant " + tenantId));
        if (ANONYMIZED.equals(account.status())) {
            Instant at = account.anonymizedAt()
                    .orElseThrow(() -> new IllegalStateException("An anonymized account without anonymized_at"));
            return new AccountAnonymization(userId, at, 0, true);
        }
        int revoked = accounts.revokeSessions(tenantId, userId, now);
        accounts.removeWallet(userId, now);
        accounts.markAnonymized(tenantId, userId);
        return new AccountAnonymization(userId, now, revoked, false);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> anonymizedAmong(Collection<UUID> userIds) {
        Objects.requireNonNull(userIds, "userIds");
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return accounts.anonymizedAmong(List.copyOf(userIds));
    }
}
