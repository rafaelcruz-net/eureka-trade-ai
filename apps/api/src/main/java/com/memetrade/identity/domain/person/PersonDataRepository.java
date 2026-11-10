package com.memetrade.identity.domain.person;

import com.memetrade.identity.api.PersonDataPort.AcceptedVersion;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read port of J1 (u4-data-requests): the account, its wallet, its sessions and its acceptances, by person. */
public interface PersonDataRepository {

    /** The person of {@code tenantId}; empty when the person does not exist in that tenant. */
    Optional<PersonRow> findPerson(UUID tenantId, UUID userId);

    /** Every accepted version of the person, oldest first. */
    List<AcceptedVersion> acceptedVersions(UUID tenantId, UUID userId);

    /** The account of {@code userId} in any tenant (administrative use only). */
    Optional<AccountRow> findAccount(UUID userId);

    /**
     * One person.
     *
     * @param walletAddress the full address, or empty once the wallet was removed; never printed
     * @param lastLoginAt the start of the most recent session, or empty
     */
    record PersonRow(
            Optional<String> walletAddress,
            Optional<String> provider,
            Instant createdAt,
            Optional<Instant> lastLoginAt) {
        public PersonRow {
            Objects.requireNonNull(walletAddress, "walletAddress");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(lastLoginAt, "lastLoginAt");
        }

        @Override
        public String toString() {
            return "PersonRow[hasWallet=" + walletAddress.isPresent() + "]";
        }
    }

    /**
     * One account.
     *
     * @param status the status column of the account ({@code ACTIVE}, {@code DISABLED}, or {@code ANONYMIZED} from
     *     u5-anonymization)
     * @param walletAddress the full address, or empty once the wallet was removed; never printed
     */
    record AccountRow(UUID userId, UUID tenantId, String status, Optional<String> walletAddress) {
        public AccountRow {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(walletAddress, "walletAddress");
        }

        @Override
        public String toString() {
            return "AccountRow[userId=" + userId + ", status=" + status + ", hasWallet=" + walletAddress.isPresent()
                    + "]";
        }
    }
}
