package com.memetrade.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * J1 (contract-summary.md, u4-data-requests): what the Privacy module may know of a person, without reading a table of
 * Identity. Only Privacy uses it; the methods of u5-anonymization are not here yet.
 *
 * <ul>
 *   <li>{@link #personSummary(UUID, UUID)}: the data of the person for the Access summary (BR4.8), the wallet already
 *       masked;
 *   <li>{@link #findAccount(UUID)}: the tenant and the state of an account, for the administrator only (BR4.10);
 *   <li>{@link #verifyAccountSignature(UUID, String, String)}: whether the wallet of the account signed a verification
 *       message (BR4.11, BR4.12), with the native Ed25519 of the JDK. A message that does not start with
 *       {@link #VERIFICATION_FIRST_LINE}, a login message included, is always refused (AC5.6.7).
 * </ul>
 *
 * The full wallet address and the signature never leave this interface, and the signature is never stored or logged.
 */
public interface PersonDataPort {

    /**
     * The fixed first line of every verification message of a data subject request (BR4.11). A login message starts
     * with the domain of the app, so it never starts with this line.
     */
    String VERIFICATION_FIRST_LINE = "MemeTrade - verificação de pedido de titular";

    /**
     * The summary of the person of {@code tenantId}; empty when the person does not exist in that tenant or has no
     * wallet any more (anonymised).
     */
    Optional<PersonSummary> personSummary(UUID tenantId, UUID userId);

    /** The account of {@code userId} with its tenant and state; empty when there is no such account. */
    Optional<AccountRef> findAccount(UUID userId);

    /**
     * Whether {@code signature} (Base58, 64 bytes) is the Ed25519 signature of the wallet of the ACTIVE account
     * {@code userId} over the exact UTF-8 {@code message}. False for a message without the verification first line, an
     * anonymised or unknown account, a malformed signature or the signature of another wallet; never throws for bad
     * input.
     */
    boolean verifyAccountSignature(UUID userId, String message, String signature);

    /** The state of an account as Privacy sees it: ANONYMIZED once its wallet was removed (u5-anonymization). */
    enum AccountState {
        ACTIVE,
        ANONYMIZED
    }

    /** An account, only for the administrative use of Privacy (BR4.10). */
    record AccountRef(UUID userId, UUID tenantId, AccountState state) {
        public AccountRef {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(state, "state");
        }
    }

    /** One accepted version of the terms (UTC). */
    record AcceptedVersion(String version, Instant acceptedAt) {
        public AcceptedVersion {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
        }
    }

    /**
     * The data of the person for the Access summary (BR4.8): never the full wallet.
     *
     * @param walletMasked the wallet as {@code 4 + "…" + 4}
     * @param lastLoginAt the start of the most recent session; empty before the first login
     * @param acceptedVersions every accepted version, oldest first
     */
    record PersonSummary(
            String walletMasked,
            String provider,
            Instant accountCreatedAt,
            Optional<Instant> lastLoginAt,
            List<AcceptedVersion> acceptedVersions) {
        public PersonSummary {
            Objects.requireNonNull(walletMasked, "walletMasked");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(accountCreatedAt, "accountCreatedAt");
            Objects.requireNonNull(lastLoginAt, "lastLoginAt");
            acceptedVersions = List.copyOf(acceptedVersions);
        }
    }
}
