package com.memetrade.txsigner.proof;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.txsigner.codec.Ed25519Keys;
import com.memetrade.txsigner.domain.kms.KmsPort;
import com.memetrade.txsigner.testsupport.KmsSimulator;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Proof 1 against the in-memory simulator; runs in the CI (NFR8.4: no real KMS in the CI). */
class KmsEd25519ProofTest extends KmsEd25519Contract {

    private final KmsSimulator simulator = new KmsSimulator();

    @Override
    protected KmsPort kms() {
        return simulator;
    }

    @Override
    protected void changeTag(String keyRef, Map<String, String> tags) {
        simulator.tagResource(keyRef, tags);
    }

    @Override
    protected void createWithRawTags(Map<String, String> tags) {
        simulator.createKeyWithTags(tags);
    }

    @Override
    protected byte[] publicKeyDer(String keyRef) {
        return simulator.publicKeyDer(keyRef);
    }

    @Test
    void aPublicKeyThatIsNotEd25519IsRefused() {
        // DER SubjectPublicKeyInfo of a P-256 key prefix: never accepted as a Solana address.
        byte[] p256 =
                HexFormat.of().parseHex("3059301306072a8648ce3d020106082a8648ce3d03010703420004" + "00".repeat(64));

        assertThatThrownBy(() -> Ed25519Keys.rawFromSpki(p256)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTruncatedEd25519PublicKeyIsRefused() {
        byte[] truncated = HexFormat.of().parseHex("302a300506032b6570032100" + "11".repeat(31));

        assertThatThrownBy(() -> Ed25519Keys.rawFromSpki(truncated)).isInstanceOf(IllegalArgumentException.class);
    }
}
