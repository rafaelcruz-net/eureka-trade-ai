package com.memetrade.identity.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * J1 of u5-anonymization (contract-summary.md J1; BR5.2, BR5.5 to BR5.7): what the Privacy module may ask Identity
 * about the anonymization of an account, without reading a table of Identity. Only Privacy uses it.
 *
 * <ul>
 *   <li>{@link #target(UUID)}: the tenant, the role and the state of the account, read without a lock, for the checks
 *       of BR5.1 before any lock;
 *   <li>{@link #anonymizeAccount(UUID, UUID, Instant)}: inside the transaction of Privacy ({@code MANDATORY}), locks the
 *       sessions of the person (by {@code session_id}) and then the account, revokes every session, deletes the login
 *       challenges of the address, removes the address and the provider of the wallet and records {@code ANONYMIZED};
 *       an account already anonymized changes nothing (BR5.5);
 *   <li>{@link #anonymizedAmong(Collection)}: which of the accounts are anonymized, for the administrator list.
 * </ul>
 *
 * The full wallet address never leaves this interface.
 */
public interface AccountAnonymizationPort {

    /** The account of {@code userId} in any tenant; empty when there is no such account. Read only, no lock. */
    Optional<AnonymizationTarget> target(UUID userId);

    /**
     * Anonymizes the account of {@code userId} in {@code tenantId} at {@code now}. Runs only inside an existing
     * transaction (Propagation.MANDATORY); a lock not obtained within the {@code lock_timeout} of the caller fails and
     * the caller's transaction rolls back everything.
     *
     * @throws IllegalArgumentException when the account does not exist in that tenant
     */
    AccountAnonymization anonymizeAccount(UUID tenantId, UUID userId, Instant now);

    /** The accounts among {@code userIds} whose status is ANONYMIZED; empty for an empty input. */
    Set<UUID> anonymizedAmong(Collection<UUID> userIds);

    /**
     * An account as the anonymization sees it.
     *
     * @param anonymized whether the status is ANONYMIZED
     * @param anonymizedAt when the wallet lost its address; present exactly for an anonymized account
     */
    record AnonymizationTarget(
            UUID userId, UUID tenantId, Role role, boolean anonymized, Optional<Instant> anonymizedAt) {
        public AnonymizationTarget {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(anonymizedAt, "anonymizedAt");
        }
    }

    /**
     * What the anonymization of the account did (J1 part of entities.md AnonymizationResult): the same instant on a
     * repetition, with {@code alreadyAnonymized} true and nothing revoked.
     */
    record AccountAnonymization(UUID userId, Instant anonymizedAt, int sessionsRevoked, boolean alreadyAnonymized) {
        public AccountAnonymization {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(anonymizedAt, "anonymizedAt");
            if (sessionsRevoked < 0) {
                throw new IllegalArgumentException("sessionsRevoked must not be negative");
            }
        }
    }
}
