package com.memetrade.txsigner.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.txsigner.codec.Base58;
import com.memetrade.txsigner.codec.Ed25519Keys;
import com.memetrade.txsigner.domain.kms.KeyTags;
import com.memetrade.txsigner.domain.kms.KmsPort;
import com.memetrade.txsigner.domain.kms.KmsRefusedException;
import com.memetrade.txsigner.policy.Ed25519Verifier;
import com.memetrade.txsigner.testsupport.TestWallet;
import com.memetrade.txsigner.testsupport.TxBytes;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Technical proof 1 of the B1 (code-generation-plan.md Step 3, bolt-plan P0, NFR1.1): the KMS creates an Ed25519 key
 * with the four tags in the creation itself, signs a Solana transfer message with a 64-byte pure Ed25519 signature that
 * verifies against the address derived from GetPublicKey, refuses to change a tag and refuses a creation without
 * {@code mainWalletMac}. The same contract runs against the in-memory simulator (CI) and, by hand, the real AWS KMS.
 */
abstract class KmsEd25519Contract {

    protected abstract KmsPort kms();

    /** Calls TagResource on an existing key with the raw KMS API (no port operation exists for it). */
    protected abstract void changeTag(String keyRef, Map<String, String> tags);

    /** Calls CreateKey with the given raw tags (no port operation creates a key without the four tags). */
    protected abstract void createWithRawTags(Map<String, String> tags);

    /** DER SubjectPublicKeyInfo returned by GetPublicKey. */
    protected abstract byte[] publicKeyDer(String keyRef);

    private final TestWallet mainWallet = TestWallet.generate();

    private KeyTags tags() {
        String mac = java.util.HexFormat.of()
                .formatHex(kms().mac(mainWallet.address().getBytes(StandardCharsets.UTF_8)));
        return new KeyTags(mac, UUID.randomUUID().toString(), "LOCALNET");
    }

    @Test
    void theKeyIsBornWithTheFourTags() {
        KeyTags tags = tags();

        String keyRef = kms().createKey(tags);

        assertThat(kms().tags(keyRef)).containsAllEntriesOf(tags.asMap()).hasSize(4);
        assertThat(kms().listKeyRefs()).contains(keyRef);
    }

    @Test
    void theAddressComesFromTheDerPublicKeyOfGetPublicKey() {
        String keyRef = kms().createKey(tags());

        byte[] raw = Ed25519Keys.rawFromSpki(publicKeyDer(keyRef));

        assertThat(raw).hasSize(32).isEqualTo(kms().publicKey(keyRef));
        assertThat(Base58.encode(raw)).hasSizeBetween(32, 44);
    }

    @Test
    void theSignatureOfATransferIs64BytesOfPureEd25519OverTheMessage() {
        String keyRef = kms().createKey(tags());
        byte[] bot = kms().publicKey(keyRef);
        byte[] message = TxBytes.withdrawal(bot, mainWallet.publicKey(), 1_000_000L, 450, 1L)
                .build();

        byte[] signature = kms().sign(keyRef, message);

        assertThat(signature).hasSize(64);
        assertThat(new Ed25519Verifier().verify(bot, message, signature)).isTrue();
        byte[] tampered = message.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThat(new Ed25519Verifier().verify(bot, tampered, signature)).isFalse();
    }

    @Test
    void theSignedTransactionHasTheSolanaWireShape() {
        String keyRef = kms().createKey(tags());
        byte[] message = TxBytes.withdrawal(kms().publicKey(keyRef), mainWallet.publicKey(), 5L, null, null)
                .build();

        byte[] signature = kms().sign(keyRef, message);
        byte[] wire = TxBytes.transaction(message, signature);

        assertThat(wire[0]).isEqualTo((byte) 1);
        assertThat(wire).hasSize(1 + 64 + message.length);
        assertThat(Base58.encode(signature)).hasSizeBetween(86, 88);
    }

    @Test
    void changingATagAfterTheCreationIsRefused() {
        String keyRef = kms().createKey(tags());

        assertThatThrownBy(() -> changeTag(keyRef, Map.of(KeyTags.MAIN_WALLET_MAC, "00")))
                .isInstanceOf(KmsRefusedException.class);
        assertThat(kms().tags(keyRef).get(KeyTags.MAIN_WALLET_MAC)).isNotEqualTo("00");
    }

    @Test
    void creatingAKeyWithoutMainWalletMacIsRefused() {
        Map<String, String> withoutMac = Map.of(
                KeyTags.APP,
                KeyTags.APP_VALUE,
                KeyTags.CREATION_REQUEST_ID,
                UUID.randomUUID().toString(),
                KeyTags.NETWORK,
                "LOCALNET");

        assertThatThrownBy(() -> createWithRawTags(withoutMac)).isInstanceOf(KmsRefusedException.class);
    }

    @Test
    void theAnchorMacVerifiesOnlyTheSameMainWallet() {
        byte[] address = mainWallet.address().getBytes(StandardCharsets.UTF_8);
        byte[] other = TestWallet.generate().address().getBytes(StandardCharsets.UTF_8);

        byte[] mac = kms().mac(address);

        assertThat(mac).hasSize(32);
        assertThat(kms().verifyMac(address, mac)).isTrue();
        assertThat(kms().verifyMac(other, mac)).isFalse();
    }
}
