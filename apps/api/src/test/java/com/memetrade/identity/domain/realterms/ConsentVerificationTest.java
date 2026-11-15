package com.memetrade.identity.domain.realterms;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.domain.signature.Ed25519SignatureVerifier;
import com.memetrade.testsupport.TestWallet;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Step 15, written before the code (M9, BR1.5, AC1.2.3): the API checks the consent signature with the native Ed25519
 * against the main wallet of the person; another wallet, another text, a malformed signature and the signature of the
 * login message never pass for a consent.
 */
class ConsentVerificationTest {

    private static final String CONSENT = "Eureka Trader - consentimento REAL v1\npurpose: ACTIVATE";

    private final TestWallet main = TestWallet.generate();
    private final RealIdentityService identity = new RealIdentityService(
            (user, tenant, version) -> true, (tenant, user) -> Optional.empty(), new Ed25519SignatureVerifier(), "v3");

    @Test
    void theSignatureOfTheMainWalletOverTheExactTextPasses() {
        assertThat(identity.verifyConsentSignature(main.address(), CONSENT, main.sign(CONSENT)))
                .isTrue();
    }

    @Test
    void anotherWalletOrAnotherTextNeverPasses() {
        TestWallet other = TestWallet.generate();

        assertThat(identity.verifyConsentSignature(main.address(), CONSENT, other.sign(CONSENT)))
                .isFalse();
        assertThat(identity.verifyConsentSignature(main.address(), CONSENT + " ", main.sign(CONSENT)))
                .isFalse();
    }

    @Test
    void theSignatureOfTheLoginMessageIsNeverAConsent() {
        String login = "app pede que você entre com a sua conta Solana";

        assertThat(identity.verifyConsentSignature(main.address(), CONSENT, main.sign(login)))
                .isFalse();
    }

    @Test
    void aMalformedSignatureOrAddressFailsClosed() {
        assertThat(identity.verifyConsentSignature(main.address(), CONSENT, "0OIl"))
                .isFalse();
        assertThat(identity.verifyConsentSignature("not-an-address", CONSENT, main.sign(CONSENT)))
                .isFalse();
        assertThat(identity.verifyConsentSignature(main.address(), null, main.sign(CONSENT)))
                .isFalse();
    }
}
