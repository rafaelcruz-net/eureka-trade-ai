package com.memetrade.txsigner.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.txsigner.testsupport.ConsentText;
import com.memetrade.txsigner.testsupport.TestWallet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Step 6, written before the code (BR1.5, BR1.8, BR5.3, AC1.2.3, NFR1.8): the TxSigner checks the WHOLE canonical
 * consent again, without trusting the api, and refuses the login message, a repeated nonce, an expired consent, another
 * network, swapped fields, a cap above 10 SOL and any non-canonical text.
 */
class ConsentVerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:01:00Z");
    private static final long TEN_SOL = 10_000_000_000L;

    private final TestWallet main = TestWallet.generate();
    private final Set<String> usedNonces = new HashSet<>();
    private final ConsentVerifier verifier = new ConsentVerifier(
            Clock.fixed(NOW, ZoneOffset.UTC), new Ed25519Verifier(), "LOCALNET", TEN_SOL, usedNonces::contains);

    private ConsentCheck activation(String message) {
        return ConsentCheck.activation(message, main.sign(message), main.address());
    }

    private ConsentRefusal refusalOf(ConsentCheck check) {
        try {
            verifier.verify(check);
        } catch (ConsentRefusedException refused) {
            return refused.refusal();
        }
        throw new AssertionError("the consent was accepted");
    }

    @Test
    void aCanonicalActivationSignedByTheMainWalletIsAccepted() {
        String message = ConsentText.activation(main.address()).text();

        VerifiedConsent consent = verifier.verify(activation(message));

        assertThat(consent.purpose()).isEqualTo(ConsentPurpose.ACTIVATE);
        assertThat(consent.mode()).isEqualTo(OperationMode.SIGNED);
        assertThat(consent.capLamports()).isEqualTo(2_000_000_000L);
        assertThat(consent.nonce()).isEqualTo("8qbHbw2BbbTHBW1sbeqakYXVKRQM8Ne7pLK7m6CVfeR");
        assertThat(consent.expiresAt()).isEqualTo(Instant.parse("2026-11-14T12:05:00Z"));
        assertThat(consent.messageDigest()).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void theLoginMessageIsNeverAConsent() {
        String login = String.join(
                "\n",
                "app.eureka pede que você entre com a sua conta Solana",
                main.address(),
                "Esta assinatura serve só para entrar e não autoriza operações.",
                "URI: https://app.eureka",
                "Chain: solana:mainnet",
                "Nonce: abcdefghijklmnop",
                "Issued At: 2026-11-14T12:00:00Z",
                "Expiration Time: 2026-11-14T12:05:00Z");

        assertThat(refusalOf(activation(login))).isEqualTo(ConsentRefusal.MALFORMED);
        assertThat(refusalOf(activation(ConsentText.activation(main.address())
                        .title("Eureka Trader - login")
                        .text())))
                .isEqualTo(ConsentRefusal.MALFORMED);
    }

    @Test
    void aNonceAlreadyUsedIsRefused() {
        String message = ConsentText.activation(main.address()).text();
        usedNonces.add("8qbHbw2BbbTHBW1sbeqakYXVKRQM8Ne7pLK7m6CVfeR");

        assertThat(refusalOf(activation(message))).isEqualTo(ConsentRefusal.NONCE_USED);
    }

    @Test
    void anExpiredConsentIsRefusedAsExpired() {
        String message = ConsentText.activation(main.address())
                .issuedAt(Instant.parse("2026-11-14T11:50:00Z"))
                .expiresAt(Instant.parse("2026-11-14T11:55:00Z"))
                .text();

        ConsentRefusedException refused = catchRefusal(activation(message));

        assertThat(refused.refusal()).isEqualTo(ConsentRefusal.EXPIRED);
        assertThat(refused.code()).isEqualTo("CONSENT_EXPIRED");
    }

    @Test
    void anotherNetworkIsRefused() {
        String message =
                ConsentText.activation(main.address()).network("MAINNET").text();

        assertThat(refusalOf(activation(message))).isEqualTo(ConsentRefusal.NETWORK_MISMATCH);
    }

    @Test
    void aPurposeDifferentFromTheRequestIsRefused() {
        String raiseCap =
                ConsentText.activation(main.address()).purpose("RAISE_CAP").text();

        assertThat(refusalOf(activation(raiseCap))).isEqualTo(ConsentRefusal.PURPOSE_MISMATCH);
    }

    @Test
    void theBotWalletLineMustBeNewOnActivationAndTheTargetWalletOnChanges() {
        String target = TestWallet.generate().address();
        String activationWithAddress =
                ConsentText.activation(main.address()).botWallet(target).text();
        String raiseWithNew =
                ConsentText.activation(main.address()).purpose("RAISE_CAP").text();
        String raiseForAnother = ConsentText.activation(main.address())
                .purpose("RAISE_CAP")
                .botWallet(TestWallet.generate().address())
                .text();
        String raiseForTarget = ConsentText.activation(main.address())
                .purpose("RAISE_CAP")
                .botWallet(target)
                .text();

        assertThat(refusalOf(activation(activationWithAddress))).isEqualTo(ConsentRefusal.BOT_WALLET_MISMATCH);
        assertThat(refusalOf(change(raiseWithNew, target))).isEqualTo(ConsentRefusal.BOT_WALLET_MISMATCH);
        assertThat(refusalOf(change(raiseForAnother, target))).isEqualTo(ConsentRefusal.BOT_WALLET_MISMATCH);
        assertThat(verifier.verify(change(raiseForTarget, target)).purpose()).isEqualTo(ConsentPurpose.RAISE_CAP);
    }

    @Test
    void aCapAboveTenSolIsRefusedEvenIfTheApiSentIt() {
        String message = ConsentText.activation(main.address())
                .capLamports("10000000001")
                .text();

        assertThat(refusalOf(activation(message))).isEqualTo(ConsentRefusal.CAP_ABOVE_MAXIMUM);
    }

    @Test
    void aCapThatOverflowsSixtyFourBitsIsMalformed() {
        String message = ConsentText.activation(main.address())
                .capLamports("99999999999999999999")
                .text();

        assertThat(refusalOf(activation(message))).isEqualTo(ConsentRefusal.MALFORMED);
    }

    @Test
    void theActivationAlwaysAuthorizesTheSignedMode() {
        String message =
                ConsentText.activation(main.address()).mode("AUTO_FULL").text();

        assertThat(refusalOf(activation(message))).isEqualTo(ConsentRefusal.MODE_NOT_ALLOWED);
    }

    @Test
    void aSignatureOfAnotherWalletOrOfAnotherTextIsRefused() {
        String message = ConsentText.activation(main.address()).text();
        String altered =
                ConsentText.activation(main.address()).capLamports("3000000000").text();
        TestWallet attacker = TestWallet.generate();

        assertThat(refusalOf(ConsentCheck.activation(message, attacker.sign(message), main.address())))
                .isEqualTo(ConsentRefusal.BAD_SIGNATURE);
        assertThat(refusalOf(ConsentCheck.activation(altered, main.sign(message), main.address())))
                .isEqualTo(ConsentRefusal.BAD_SIGNATURE);
        assertThat(refusalOf(ConsentCheck.activation(message, "not-base58-0OIl", main.address())))
                .isEqualTo(ConsentRefusal.BAD_SIGNATURE);
    }

    @Test
    void theMainWalletOfTheMessageMustBeTheOneOfTheRequest() {
        TestWallet other = TestWallet.generate();
        String message = ConsentText.activation(other.address()).text();

        assertThat(refusalOf(ConsentCheck.activation(message, other.sign(message), main.address())))
                .isEqualTo(ConsentRefusal.MAIN_WALLET_MISMATCH);
    }

    @Test
    void aNonCanonicalTextIsRefused() {
        String trailingNewline =
                ConsentText.activation(main.address()).trailer("\n").text();
        String trailingSpace =
                ConsentText.activation(main.address()).trailer(" ").text();
        String crlf = ConsentText.activation(main.address()).text().replace("\n", "\r\n");
        String paddedCap = ConsentText.activation(main.address())
                .capLamports("02000000000")
                .text();
        String shortNonce = ConsentText.activation(main.address()).nonce("abc").text();

        for (String message : new String[] {trailingNewline, trailingSpace, crlf, paddedCap, shortNonce}) {
            assertThat(refusalOf(activation(message))).isEqualTo(ConsentRefusal.MALFORMED);
        }
        assertThat(refusalOf(activation("x".repeat(ConsentMessage.MAX_LENGTH + 1))))
                .isEqualTo(ConsentRefusal.MALFORMED);
    }

    @Test
    void theValidityIsExactlyFiveMinutesAndNeverIssuedInTheFuture() {
        String sixMinutes = ConsentText.activation(main.address())
                .expiresAt(Instant.parse("2026-11-14T12:06:00Z"))
                .text();
        String future = ConsentText.activation(main.address())
                .issuedAt(Instant.parse("2026-11-14T12:03:00Z"))
                .expiresAt(Instant.parse("2026-11-14T12:08:00Z"))
                .text();

        assertThat(refusalOf(activation(sixMinutes))).isEqualTo(ConsentRefusal.MALFORMED);
        assertThat(refusalOf(activation(future))).isEqualTo(ConsentRefusal.MALFORMED);
    }

    private ConsentCheck change(String message, String targetBotWallet) {
        return ConsentCheck.change(
                message, main.sign(message), main.address(), ConsentPurpose.RAISE_CAP, targetBotWallet);
    }

    private ConsentRefusedException catchRefusal(ConsentCheck check) {
        try {
            verifier.verify(check);
        } catch (ConsentRefusedException refused) {
            return refused;
        }
        throw new AssertionError("the consent was accepted");
    }

    @Test
    void everyRefusalMapsToConsentInvalidExceptExpiry() {
        assertThatThrownBy(() -> verifier.verify(activation("garbage")))
                .isInstanceOfSatisfying(
                        ConsentRefusedException.class, e -> assertThat(e.code()).isEqualTo("CONSENT_INVALID"));
    }
}
