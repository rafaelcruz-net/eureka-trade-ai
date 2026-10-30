package com.memetrade.identity.domain.signature;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.domain.signature.Ed25519SignatureVerifier.Result;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestWallet;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u1")
class Ed25519SignatureVerifierTest {

    private final Ed25519SignatureVerifier verifier = new Ed25519SignatureVerifier();

    @Test
    void acceptsTheRfc8032TestVector1() {
        // RFC 8032 section 7.1, TEST 1 (empty message). Public test vector, not a real wallet.
        byte[] publicKey = HexFormat.of().parseHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        byte[] signature = HexFormat.of()
                .parseHex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bac"
                        + "c61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");

        assertThat(verifier.verify(SyntheticIds.encode(publicKey), "", SyntheticIds.encode(signature)))
                .isEqualTo(Result.VALID);
    }

    @Test
    void acceptsAValidSignatureOverTheExactMessage() {
        TestWallet wallet = TestWallet.generate();
        String message = "localhost:3000 pede que você entre com a sua conta Solana";

        assertThat(verifier.verify(wallet.address(), message, wallet.sign(message)))
                .isEqualTo(Result.VALID);
    }

    @Test
    void refusesAnAlteredMessage() {
        TestWallet wallet = TestWallet.generate();
        String signature = wallet.sign("Nonce: abc");

        assertThat(verifier.verify(wallet.address(), "Nonce: abd", signature)).isEqualTo(Result.INVALID);
    }

    @Test
    void refusesASignatureMadeByAnotherWallet() {
        TestWallet owner = TestWallet.generate();
        TestWallet attacker = TestWallet.generate();
        String message = "login";

        assertThat(verifier.verify(owner.address(), message, attacker.sign(message)))
                .isEqualTo(Result.INVALID);
    }

    @Test
    void refusesKeysAndSignaturesWithTheWrongSize() {
        TestWallet wallet = TestWallet.generate();
        byte[] signature = wallet.signRaw("m");
        String shortSignature = SyntheticIds.encode(Arrays.copyOf(signature, 63));
        String shortKey = SyntheticIds.encode(new byte[] {1, 2, 3});

        assertThat(verifier.verify(wallet.address(), "m", shortSignature)).isEqualTo(Result.MALFORMED);
        assertThat(verifier.verify(shortKey, "m", wallet.sign("m"))).isEqualTo(Result.MALFORMED);
    }

    @Test
    void refusesCharactersOutsideTheBase58Alphabet() {
        TestWallet wallet = TestWallet.generate();
        String signature = wallet.sign("m");

        assertThat(verifier.verify(wallet.address(), "m", "0" + signature.substring(1)))
                .isEqualTo(Result.MALFORMED);
        assertThat(verifier.verify("O" + wallet.address().substring(1), "m", signature))
                .isEqualTo(Result.MALFORMED);
    }

    @Test
    void refusesATamperedSignatureByte() {
        TestWallet wallet = TestWallet.generate();
        byte[] signature = wallet.signRaw("m");
        signature[10] ^= 0x01;

        assertThat(verifier.verify(wallet.address(), "m", SyntheticIds.encode(signature)))
                .isEqualTo(Result.INVALID);
    }
}
