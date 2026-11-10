package com.memetrade.identity.domain.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.domain.terms.CurrentTerms;
import com.memetrade.identity.domain.terms.TermsAcceptanceLookup;
import com.memetrade.identity.domain.terms.TermsDocument;
import com.memetrade.identity.domain.terms.TermsStatus;
import com.memetrade.shared.wallet.WalletAddress;
import com.memetrade.testsupport.TestWallet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 5 of u3-reacceptance (BR3.10, L2; BR5.1 to BR5.4 of U3 of the product): the Account carries the TermsStatus of
 * the person, computed with the same decision as the reacceptance lock, besides the existing fields.
 */
@Tag("u3-reacceptance")
class AccountQueryServiceTest {

    private static final Instant ACCEPTED = Instant.parse("2026-10-20T10:00:00Z");
    private static final Instant SESSION_END = Instant.parse("2026-11-10T22:00:00Z");

    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final String wallet = TestWallet.generate().address();
    private final FakeAccounts accounts = new FakeAccounts();
    private final FakeTerms terms = new FakeTerms("v2");
    private final FakeLookup lookup = new FakeLookup();
    private final AccountQueryService service = new AccountQueryService(accounts, terms, lookup);

    @Test
    void anAccountWhoseLatestAcceptanceIsTheCurrentVersionIsUpToDateWithoutAnotherQuery() {
        accounts.details = details("v2");

        AccountQueryService.AccountView view =
                service.view(userId, tenantId, sessionId).orElseThrow();

        assertThat(view.terms()).contains(new TermsStatus("v2", "v2", ACCEPTED, true));
        assertThat(lookup.asked).isEmpty();
    }

    @Test
    void anAccountWhoAcceptedOnlyAnEarlierVersionIsNotUpToDate() {
        accounts.details = details("v1");

        AccountQueryService.AccountView view =
                service.view(userId, tenantId, sessionId).orElseThrow();

        assertThat(view.terms()).contains(new TermsStatus("v1", "v2", ACCEPTED, false));
        assertThat(lookup.asked).containsExactly(userId + "|" + tenantId + "|v2");
    }

    /** The current version went back to a version accepted earlier: up to date, as the lock decides (BR3.1). */
    @Test
    void anEarlierAcceptanceOfTheCurrentVersionKeepsTheAccountUpToDate() {
        accounts.details = details("v3");
        lookup.accepted.add(userId + "|" + tenantId + "|v2");

        assertThat(service.view(userId, tenantId, sessionId).orElseThrow().terms())
                .contains(new TermsStatus("v3", "v2", ACCEPTED, true));
    }

    @Test
    void anAccountWithoutAnyAcceptanceHasNoStatusNorAcceptedTerms() {
        accounts.details = details(null);

        AccountQueryService.AccountView view =
                service.view(userId, tenantId, sessionId).orElseThrow();

        assertThat(view.terms()).isEmpty();
        assertThat(view.acceptedTerms()).isEmpty();
        assertThat(lookup.asked).isEmpty();
    }

    @Test
    void theOtherFieldsOfTheAccountAreKept() {
        accounts.details = details("v2");

        AccountQueryService.AccountView view =
                service.view(userId, tenantId, sessionId).orElseThrow();

        assertThat(view.walletAddressMasked())
                .isEqualTo(WalletAddress.mask(wallet))
                .doesNotContain(wallet);
        assertThat(view.provider()).isEqualTo("PHANTOM");
        assertThat(view.sessionExpiresAt()).isEqualTo(SESSION_END);
        assertThat(view.realMode()).isEqualTo(AccountQueryService.REAL_MODE_UNAVAILABLE);
        assertThat(view.acceptedTerms().orElseThrow().document().termsText()).isEqualTo("terms v2");
    }

    @Test
    void theAccountIsReadByThePersonTheTenantAndTheSessionOfTheToken() {
        accounts.details = details("v2");

        service.view(userId, tenantId, sessionId);

        assertThat(accounts.asked).containsExactly(userId + "|" + tenantId + "|" + sessionId);
    }

    @Test
    void anUnknownAccountIsEmpty() {
        assertThat(service.view(userId, tenantId, sessionId)).isEmpty();
    }

    private AccountDetailsRepository.AccountDetails details(String version) {
        return new AccountDetailsRepository.AccountDetails(
                wallet, "PHANTOM", SESSION_END, version, version == null ? null : ACCEPTED);
    }

    static final class FakeAccounts implements AccountDetailsRepository {
        AccountDetails details;
        final List<String> asked = new ArrayList<>();

        @Override
        public Optional<AccountDetails> find(UUID userId, UUID tenantId, UUID sessionId) {
            asked.add(userId + "|" + tenantId + "|" + sessionId);
            return Optional.ofNullable(details);
        }
    }

    static final class FakeTerms implements CurrentTerms {
        private final String current;

        FakeTerms(String current) {
            this.current = current;
        }

        @Override
        public TermsDocument current() {
            return new TermsDocument(current, "terms " + current, "risk " + current);
        }

        @Override
        public Optional<TermsDocument> find(String version) {
            return Optional.of(new TermsDocument(version, "terms " + version, "risk " + version));
        }
    }

    static final class FakeLookup implements TermsAcceptanceLookup {
        final Set<String> accepted = new HashSet<>();
        final List<String> asked = new ArrayList<>();

        @Override
        public boolean hasAccepted(UUID userId, UUID tenantId, String version) {
            String key = userId + "|" + tenantId + "|" + version;
            asked.add(key);
            return accepted.contains(key);
        }
    }
}
