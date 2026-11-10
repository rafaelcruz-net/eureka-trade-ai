package com.memetrade.identity.domain.anonymization;

import com.memetrade.identity.api.AccountAnonymizationPort.AnonymizationTarget;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence of J1 of u5-anonymization (performance-design.md NFR3.1): a fixed number of statements, each by the key of
 * the person or of the address, through the indexes of Identity ({@code app_user_pkey}, {@code user_session_user_idx},
 * {@code wallet.user_id}, {@code login_challenge_wallet_idx} and the primary key of {@code idempotency_record}). Runs in
 * the transaction of the caller; the locks follow the global order of BR5.6 (sessions, then the account).
 */
public interface AccountAnonymizationRepository {

    /** The account of {@code userId} with its role, state and {@code wallet.anonymized_at}; no lock. */
    Optional<AnonymizationTarget> findTarget(UUID userId);

    /** Locks every session of the person, ordered by {@code session_id}; returns their ids. */
    List<UUID> lockSessions(UUID tenantId, UUID userId);

    /**
     * The address of the wallet of the person, read WITHOUT a lock after the sessions and before the account: its
     * challenges are deleted before the account lock, so a login in flight (which holds its consumed challenge and then
     * takes the shared account lock) finishes first and never forms a cycle with the anonymization.
     */
    Optional<String> walletOf(UUID tenantId, UUID userId);

    /** Locks the account (exclusive) after its sessions; empty when it does not exist in that tenant. */
    Optional<LockedAccount> lockAccount(UUID tenantId, UUID userId);

    /** Revokes every ACTIVE session of the person at {@code now}; runs after the account lock. */
    int revokeSessions(UUID tenantId, UUID userId, Instant now);

    /**
     * Deletes the login challenges of the address and the stored idempotent answers of their issuance (the guest and
     * the administrator challenge both keep the message, which holds the address, until it expires).
     */
    int deleteLoginChallenges(String walletAddress);

    /** Removes the address and the provider of the wallet of the person and records {@code anonymized_at}. */
    void removeWallet(UUID userId, Instant now);

    /** Records the final status ANONYMIZED. */
    void markAnonymized(UUID tenantId, UUID userId);

    /** The accounts among {@code userIds} whose status is ANONYMIZED. */
    Set<UUID> anonymizedAmong(Collection<UUID> userIds);

    /**
     * The account read under its lock.
     *
     * @param walletAddress the full address, empty once removed; never printed
     */
    record LockedAccount(String status, Optional<String> walletAddress, Optional<Instant> anonymizedAt) {
        public LockedAccount {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(walletAddress, "walletAddress");
            Objects.requireNonNull(anonymizedAt, "anonymizedAt");
        }

        @Override
        public String toString() {
            return "LockedAccount[status=" + status + ", hasWallet=" + walletAddress.isPresent() + "]";
        }
    }
}
