package com.memetrade.identity.domain.realterms;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.domain.signature.Ed25519SignatureVerifier;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Step 15, written before the code (BR1.2, AC1.1.1, AC1.1.2, team.md "a versão nova dos termos exige novo aceite ...
 * exceto"): the REAL activation needs the acceptance of v3; without the new acceptance a person still reads the bot
 * wallet and withdraws, and reaches no other REAL route.
 */
class TermsV3RealRoutesTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
        "GET,/api/v1/real/bot-wallet,true",
        "GET,/api/v1/real/bot-wallet/withdrawals,true",
        "POST,/api/v1/real/bot-wallet/withdrawals,true",
        "GET,/api/v1/real/bot-wallet/withdrawals/0b8f0d4e-6d7e-4a43-9a3c-2a8f51f0c001,true",
        "POST,/api/v1/real/bot-wallet,false",
        "PATCH,/api/v1/real/bot-wallet/preferences,false",
        "POST,/api/v1/real/bot-wallet/resume,false",
        "GET,/api/v1/real/bot-wallet/deposits,false",
        "POST,/api/v1/real/consents/challenge,false",
        "GET,/api/v1/real/bot-wallet/withdrawalsX,false",
        "GET,/api/v1/scanner,false"
    })
    void onlyTheReadOfTheWalletAndTheWithdrawalRoutesSkipTheNewAcceptance(
            String method, String path, boolean reachable) {
        assertThat(RealTermsRoutes.isReachableWithoutNewAcceptance(method, path))
                .isEqualTo(reachable);
    }

    @Test
    void theActivationNeedsTheAcceptanceOfTheRequiredVersion() {
        RealIdentityService accepted = service(Set.of("v3"));
        RealIdentityService onlyOlder = service(Set.of("v1", "v2"));

        assertThat(accepted.hasAcceptedRealTerms(TENANT, USER)).isTrue();
        assertThat(onlyOlder.hasAcceptedRealTerms(TENANT, USER)).isFalse();
    }

    @Test
    void theMainWalletIsTheOneVerifiedAtLogin() {
        RealIdentityService service = service(Set.of());

        assertThat(service.mainWallet(TENANT, USER)).contains("MainWalletOfTheTest");
        assertThat(service.mainWallet(UUID.randomUUID(), USER)).isEmpty();
    }

    private static RealIdentityService service(Set<String> acceptedVersions) {
        return new RealIdentityService(
                (userId, tenantId, version) -> acceptedVersions.contains(version),
                (tenantId, userId) -> TENANT.equals(tenantId) ? Optional.of("MainWalletOfTheTest") : Optional.empty(),
                new Ed25519SignatureVerifier(),
                "v3");
    }
}
