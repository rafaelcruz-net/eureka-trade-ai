package com.memetrade.identity.domain.anonymization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.identity.api.AccountAnonymizationPort.AccountAnonymization;
import com.memetrade.identity.api.AccountAnonymizationPort.AnonymizationTarget;
import com.memetrade.identity.api.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 2 of u5-anonymization, written BEFORE J1 (BR5.2, BR5.5, BR5.6): the order of the statements of
 * {@code anonymizeAccount} - the sessions locked first, the challenges of the address deleted before the account lock
 * (a login in flight holds its consumed challenge, so it finishes first and its session is then revoked), the account
 * locked, the sessions revoked after that lock, the wallet removed and the status written - and the repetition on an
 * account already ANONYMIZED, which writes nothing.
 */
@Tag("u5-anonymization")
class AnonymizationOfAccountTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final String ADDRESS = "So1anaTestWa11etAddre55xxxxxxxxxxxxxxxxxxx";

    private final UUID tenant = UUID.randomUUID();
    private final UUID person = UUID.randomUUID();
    private final Repository repository = new Repository();
    private final AccountAnonymizationService service = new AccountAnonymizationService(repository);

    @Test
    void theStatementsFollowTheGlobalLockOrderAndTheRevocationComesAfterTheAccountLock() {
        repository.account = Optional.of(
                new AccountAnonymizationRepository.LockedAccount("ACTIVE", Optional.of(ADDRESS), Optional.empty()));
        repository.revoked = 4;

        AccountAnonymization result = service.anonymizeAccount(tenant, person, NOW);

        assertThat(repository.calls)
                .containsExactly(
                        "lockSessions",
                        "walletOf",
                        "deleteLoginChallenges",
                        "lockAccount",
                        "revokeSessions",
                        "removeWallet",
                        "markAnonymized");
        assertThat(result).isEqualTo(new AccountAnonymization(person, NOW, 4, false));
        assertThat(repository.deletedFor).isEqualTo(ADDRESS);
    }

    @Test
    void aDisabledAccountIsAnonymizedToo() {
        repository.account = Optional.of(
                new AccountAnonymizationRepository.LockedAccount("DISABLED", Optional.of(ADDRESS), Optional.empty()));

        AccountAnonymization result = service.anonymizeAccount(tenant, person, NOW);

        assertThat(result.alreadyAnonymized()).isFalse();
        assertThat(repository.calls).contains("markAnonymized", "removeWallet");
    }

    @Test
    void anAccountAlreadyAnonymizedWritesNothingAndAnswersItsInstant() {
        Instant earlier = NOW.minusSeconds(3600);
        repository.walletAddress = Optional.empty();
        repository.account = Optional.of(
                new AccountAnonymizationRepository.LockedAccount("ANONYMIZED", Optional.empty(), Optional.of(earlier)));

        AccountAnonymization result = service.anonymizeAccount(tenant, person, NOW);

        assertThat(result).isEqualTo(new AccountAnonymization(person, earlier, 0, true));
        assertThat(repository.calls)
                .doesNotContain("deleteLoginChallenges", "revokeSessions", "removeWallet", "markAnonymized");
    }

    @Test
    void anUnknownAccountOfTheTenantIsAProgrammingErrorOfTheCaller() {
        repository.account = Optional.empty();

        assertThatThrownBy(() -> service.anonymizeAccount(tenant, person, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(ADDRESS);
        assertThat(repository.calls).doesNotContain("removeWallet", "markAnonymized", "revokeSessions");
    }

    @Test
    void theTargetAndTheAnonymizedAmongComeFromTheRepository() {
        AnonymizationTarget target = new AnonymizationTarget(person, tenant, Role.BETA_USER, false, Optional.empty());
        repository.target = Optional.of(target);
        repository.among = Set.of(person);

        assertThat(service.target(person)).contains(target);
        assertThat(service.anonymizedAmong(List.of(person, UUID.randomUUID()))).containsExactly(person);
        assertThat(service.anonymizedAmong(List.of())).isEmpty();
    }

    @Test
    void theLockedAccountNeverPrintsTheAddress() {
        AccountAnonymizationRepository.LockedAccount locked =
                new AccountAnonymizationRepository.LockedAccount("ACTIVE", Optional.of(ADDRESS), Optional.empty());

        assertThat(locked.toString()).doesNotContain(ADDRESS).contains("hasWallet=true");
    }

    private final class Repository implements AccountAnonymizationRepository {
        private final List<String> calls = new ArrayList<>();
        private Optional<LockedAccount> account = Optional.empty();
        private Optional<String> walletAddress = Optional.of(ADDRESS);
        private Optional<AnonymizationTarget> target = Optional.empty();
        private Set<UUID> among = Set.of();
        private int revoked;
        private String deletedFor;

        @Override
        public Optional<AnonymizationTarget> findTarget(UUID userId) {
            return target;
        }

        @Override
        public List<UUID> lockSessions(UUID tenantId, UUID userId) {
            calls.add("lockSessions");
            return List.of();
        }

        @Override
        public Optional<String> walletOf(UUID tenantId, UUID userId) {
            calls.add("walletOf");
            return walletAddress;
        }

        @Override
        public Optional<LockedAccount> lockAccount(UUID tenantId, UUID userId) {
            calls.add("lockAccount");
            return account;
        }

        @Override
        public int revokeSessions(UUID tenantId, UUID userId, Instant now) {
            calls.add("revokeSessions");
            return revoked;
        }

        @Override
        public int deleteLoginChallenges(String address) {
            calls.add("deleteLoginChallenges");
            deletedFor = address;
            return 1;
        }

        @Override
        public void removeWallet(UUID userId, Instant now) {
            calls.add("removeWallet");
        }

        @Override
        public void markAnonymized(UUID tenantId, UUID userId) {
            calls.add("markAnonymized");
        }

        @Override
        public Set<UUID> anonymizedAmong(Collection<UUID> userIds) {
            return userIds.isEmpty() ? Set.of() : among;
        }
    }
}
