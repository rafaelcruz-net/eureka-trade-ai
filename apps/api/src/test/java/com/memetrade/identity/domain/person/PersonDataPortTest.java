package com.memetrade.identity.domain.person;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.PersonDataPort;
import com.memetrade.identity.api.PersonDataPort.AcceptedVersion;
import com.memetrade.identity.api.PersonDataPort.AccountState;
import com.memetrade.identity.domain.signature.Ed25519SignatureVerifier;
import com.memetrade.testsupport.TestWallet;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 2 of u4-data-requests (J1; BR4.8, BR4.10 to BR4.12; AC5.6.3, AC5.6.4, AC5.6.7): the person data Identity hands
 * to Privacy. The wallet always leaves masked; an account without its wallet is ANONYMIZED; only a message that starts
 * with the verification line, signed by the wallet of the ACTIVE account, is accepted. The wallets are generated in test
 * code only.
 */
@Tag("u4-data-requests")
class PersonDataPortTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-0000-0000-00000000a002");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-00000000b001");
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant LOGIN = Instant.parse("2026-11-01T10:00:00Z");

    private final TestWallet wallet = TestWallet.generate();
    private final FakePeople people = new FakePeople();
    private final PersonDataPort port = new PersonDataService(people, new Ed25519SignatureVerifier());

    private String verificationMessage() {
        return PersonDataPort.VERIFICATION_FIRST_LINE + "\nlocalhost:3000\nPedido: " + UUID.randomUUID()
                + "\nFinalidade: verificação de pedido de titular\nVálida até: 2026-11-11T10:00:00Z";
    }

    @Test
    void theSummaryCarriesTheMaskedWalletTheDatesAndTheAcceptedVersions() {
        people.person(TENANT, USER, Optional.of(wallet.address()), Optional.of(LOGIN));
        people.acceptances.put(
                USER,
                List.of(
                        new AcceptedVersion("v1", Instant.parse("2026-01-01T00:00:00Z")),
                        new AcceptedVersion("v2", Instant.parse("2026-11-09T00:00:00Z"))));

        PersonDataPort.PersonSummary summary = port.personSummary(TENANT, USER).orElseThrow();

        assertThat(summary.walletMasked())
                .isNotEqualTo(wallet.address())
                .startsWith(wallet.address().substring(0, 4))
                .endsWith(wallet.address().substring(wallet.address().length() - 4));
        assertThat(summary.provider()).isEqualTo("PHANTOM");
        assertThat(summary.accountCreatedAt()).isEqualTo(CREATED);
        assertThat(summary.lastLoginAt()).contains(LOGIN);
        assertThat(summary.acceptedVersions())
                .extracting(AcceptedVersion::version)
                .containsExactly("v1", "v2");
        assertThat(summary.toString()).doesNotContain(wallet.address());
    }

    @Test
    void aPersonWhoNeverLoggedInHasNoLastLogin() {
        people.person(TENANT, USER, Optional.of(wallet.address()), Optional.empty());

        assertThat(port.personSummary(TENANT, USER).orElseThrow().lastLoginAt()).isEmpty();
    }

    @Test
    void anotherTenantOrAnUnknownPersonHasNoSummary() {
        people.person(TENANT, USER, Optional.of(wallet.address()), Optional.of(LOGIN));

        assertThat(port.personSummary(OTHER_TENANT, USER)).isEmpty();
        assertThat(port.personSummary(TENANT, UUID.randomUUID())).isEmpty();
    }

    @Test
    void anAccountWithoutItsWalletHasNoSummaryAndIsAnonymized() {
        people.person(TENANT, USER, Optional.empty(), Optional.of(LOGIN));

        assertThat(port.personSummary(TENANT, USER)).isEmpty();
        assertThat(port.findAccount(USER).orElseThrow().state()).isEqualTo(AccountState.ANONYMIZED);
    }

    @Test
    void findAccountGivesTheTenantAndTheStateForAnyTenant() {
        people.person(TENANT, USER, Optional.of(wallet.address()), Optional.empty());

        PersonDataPort.AccountRef account = port.findAccount(USER).orElseThrow();

        assertThat(account.tenantId()).isEqualTo(TENANT);
        assertThat(account.state()).isEqualTo(AccountState.ACTIVE);
        assertThat(port.findAccount(UUID.randomUUID())).isEmpty();
    }

    @Test
    void theAnonymizedStatusIsAnonymizedEvenWithAWalletRow() {
        people.person(TENANT, USER, Optional.of(wallet.address()), Optional.empty());
        people.status.put(USER, "ANONYMIZED");

        assertThat(port.findAccount(USER).orElseThrow().state()).isEqualTo(AccountState.ANONYMIZED);
    }

    @Test
    void theSignatureOfTheWalletOfTheAccountOverAVerificationMessageIsAccepted() {
        people.person(TENANT, USER, Optional.of(wallet.address()), Optional.empty());
        String message = verificationMessage();

        assertThat(port.verifyAccountSignature(USER, message, wallet.sign(message)))
                .isTrue();
    }

    @Test
    void theSignatureOfAnotherWalletIsRefused() {
        people.person(TENANT, USER, Optional.of(wallet.address()), Optional.empty());
        String message = verificationMessage();

        assertThat(port.verifyAccountSignature(
                        USER, message, TestWallet.generate().sign(message)))
                .isFalse();
    }

    @Test
    void aSignatureOverAnotherMessageIsRefused() {
        people.person(TENANT, USER, Optional.of(wallet.address()), Optional.empty());
        String signed = verificationMessage();

        assertThat(port.verifyAccountSignature(USER, verificationMessage(), wallet.sign(signed)))
                .isFalse();
    }

    @Test
    void aLoginMessageIsRefusedEvenWhenTheWalletSignedIt() {
        people.person(TENANT, USER, Optional.of(wallet.address()), Optional.empty());
        String login = "localhost:3000 wants you to sign in with your Solana account:\n" + wallet.address()
                + "\n\nURI: http://localhost:3000\nNonce: abc";

        assertThat(port.verifyAccountSignature(USER, login, wallet.sign(login))).isFalse();
    }

    @Test
    void theFirstLineMustBeTheWholeFirstLine() {
        people.person(TENANT, USER, Optional.of(wallet.address()), Optional.empty());
        String glued = PersonDataPort.VERIFICATION_FIRST_LINE + " extra\nlocalhost:3000";

        assertThat(port.verifyAccountSignature(USER, glued, wallet.sign(glued))).isFalse();
    }

    @Test
    void anAnonymizedOrUnknownAccountNeverVerifies() {
        people.person(TENANT, USER, Optional.of(wallet.address()), Optional.empty());
        people.status.put(USER, "ANONYMIZED");
        String message = verificationMessage();

        assertThat(port.verifyAccountSignature(USER, message, wallet.sign(message)))
                .isFalse();
        assertThat(port.verifyAccountSignature(UUID.randomUUID(), message, wallet.sign(message)))
                .isFalse();
    }

    @Test
    void malformedOrMissingInputIsRefusedWithoutAnException() {
        people.person(TENANT, USER, Optional.of(wallet.address()), Optional.empty());
        String message = verificationMessage();

        assertThat(port.verifyAccountSignature(USER, message, "not-base58-0OIl"))
                .isFalse();
        assertThat(port.verifyAccountSignature(USER, message, "")).isFalse();
        assertThat(port.verifyAccountSignature(USER, message, null)).isFalse();
        assertThat(port.verifyAccountSignature(USER, null, "abc")).isFalse();
        assertThat(port.verifyAccountSignature(null, message, "abc")).isFalse();
    }

    /** In-memory read port: one person per user id. */
    private static final class FakePeople implements PersonDataRepository {

        private final Map<UUID, UUID> tenants = new HashMap<>();
        private final Map<UUID, Optional<String>> wallets = new HashMap<>();
        private final Map<UUID, Optional<Instant>> logins = new HashMap<>();
        private final Map<UUID, String> status = new HashMap<>();
        private final Map<UUID, List<AcceptedVersion>> acceptances = new HashMap<>();

        void person(UUID tenantId, UUID userId, Optional<String> wallet, Optional<Instant> lastLogin) {
            tenants.put(userId, tenantId);
            wallets.put(userId, wallet);
            logins.put(userId, lastLogin);
            status.put(userId, "ACTIVE");
        }

        @Override
        public Optional<PersonRow> findPerson(UUID tenantId, UUID userId) {
            if (!tenantId.equals(tenants.get(userId))) {
                return Optional.empty();
            }
            return Optional.of(new PersonRow(
                    wallets.get(userId), wallets.get(userId).map(any -> "PHANTOM"), CREATED, logins.get(userId)));
        }

        @Override
        public List<AcceptedVersion> acceptedVersions(UUID tenantId, UUID userId) {
            return acceptances.getOrDefault(userId, List.of());
        }

        @Override
        public Optional<AccountRow> findAccount(UUID userId) {
            if (!tenants.containsKey(userId)) {
                return Optional.empty();
            }
            return Optional.of(new AccountRow(userId, tenants.get(userId), status.get(userId), wallets.get(userId)));
        }
    }
}
