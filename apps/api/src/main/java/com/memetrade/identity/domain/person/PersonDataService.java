package com.memetrade.identity.domain.person;

import com.memetrade.identity.api.PersonDataPort;
import com.memetrade.identity.domain.person.PersonDataRepository.AccountRow;
import com.memetrade.identity.domain.person.PersonDataRepository.PersonRow;
import com.memetrade.identity.domain.signature.Ed25519SignatureVerifier;
import com.memetrade.shared.wallet.WalletAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * J1 of u4-data-requests (BR4.8, BR4.10 to BR4.12, AC5.6.7): the person data Identity hands to Privacy. The summary
 * carries the wallet masked; an account without its wallet (removed by u5-anonymization) or with the status
 * {@code ANONYMIZED} is ANONYMIZED. The signature check accepts only a message whose first line is the verification line,
 * so a login message never confirms a request, and the login itself never accepts a verification message (it accepts
 * only the exact message of a challenge it issued by nonce).
 */
public class PersonDataService implements PersonDataPort {

    private static final String ANONYMIZED_STATUS = "ANONYMIZED";
    private static final String FIRST_LINE_WITH_BREAK = VERIFICATION_FIRST_LINE + "\n";

    private final PersonDataRepository people;
    private final Ed25519SignatureVerifier verifier;

    public PersonDataService(PersonDataRepository people, Ed25519SignatureVerifier verifier) {
        this.people = Objects.requireNonNull(people, "people");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PersonSummary> personSummary(UUID tenantId, UUID userId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Optional<PersonRow> person = people.findPerson(tenantId, userId);
        if (person.isEmpty() || person.get().walletAddress().isEmpty()) {
            return Optional.empty();
        }
        PersonRow row = person.get();
        return Optional.of(new PersonSummary(
                WalletAddress.mask(row.walletAddress().get()),
                row.provider().orElse(""),
                row.createdAt(),
                row.lastLoginAt(),
                people.acceptedVersions(tenantId, userId)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountRef> findAccount(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        return people.findAccount(userId)
                .map(account -> new AccountRef(account.userId(), account.tenantId(), state(account)));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean verifyAccountSignature(UUID userId, String message, String signature) {
        if (userId == null || message == null || signature == null || signature.isBlank()) {
            return false;
        }
        if (!message.startsWith(FIRST_LINE_WITH_BREAK)) {
            return false;
        }
        Optional<AccountRow> account = people.findAccount(userId);
        if (account.isEmpty() || state(account.get()) != AccountState.ACTIVE) {
            return false;
        }
        String wallet = account.get().walletAddress().orElseThrow();
        return verifier.verify(wallet, message, signature.trim()) == Ed25519SignatureVerifier.Result.VALID;
    }

    private static AccountState state(AccountRow account) {
        return account.walletAddress().isEmpty() || ANONYMIZED_STATUS.equals(account.status())
                ? AccountState.ANONYMIZED
                : AccountState.ACTIVE;
    }
}
