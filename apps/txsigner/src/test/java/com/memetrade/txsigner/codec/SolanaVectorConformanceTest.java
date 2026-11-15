package com.memetrade.txsigner.codec;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.txsigner.policy.Ed25519Verifier;
import com.memetrade.txsigner.testsupport.SolanaVectors;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Step 3 and 4 of the U4 (U4-NFR1.1, deviation of Q2): the OWN reader of the TxSigner agrees with the common vectors of
 * the SDK: Base58, the derivation of the associated token accounts, Ed25519, and the legacy and v0 forms of the swap
 * messages the api builds.
 */
class SolanaVectorConformanceTest {

    private final JsonNode vectors = SolanaVectors.load();

    @Test
    void theOwnBase58AgreesWithEveryVector() {
        for (JsonNode vector : vectors.path("base58")) {
            byte[] bytes = SolanaVectors.hex(vector.path("hex").asText());

            assertThat(Base58.encode(bytes)).isEqualTo(vector.path("base58").asText());
            assertThat(Base58.decode(vector.path("base58").asText())).isEqualTo(bytes);
        }
    }

    @Test
    void theOwnDerivationGivesEveryAssociatedTokenAccountOfTheSdk() {
        for (JsonNode vector : vectors.path("ata")) {
            byte[] address = ProgramAddress.associatedTokenAccount(
                    Base58.decode(vector.path("owner").asText()),
                    Base58.decode(vector.path("tokenProgram").asText()),
                    Base58.decode(vector.path("mint").asText()));

            assertThat(Base58.encode(address)).isEqualTo(vector.path("address").asText());
        }
    }

    @Test
    void anAddressOnTheCurveIsNeverAProgramAddressAndAHashOffTheCurveIs() {
        JsonNode first = vectors.path("ata").get(0);

        assertThat(ProgramAddress.isOnCurve(Base58.decode(first.path("owner").asText())))
                .as("a wallet is a point of the curve")
                .isTrue();
        assertThat(ProgramAddress.isOnCurve(Base58.decode(first.path("address").asText())))
                .as("a program derived address is off the curve")
                .isFalse();
    }

    @Test
    void theNativeVerifierAcceptsEverySignatureOfTheSdk() {
        Ed25519Verifier verifier = new Ed25519Verifier();
        for (JsonNode vector : vectors.path("signatures")) {
            assertThat(verifier.verify(
                            Base58.decode(vector.path("publicKey").asText()),
                            SolanaVectors.hex(vector.path("messageHex").asText()),
                            Base58.decode(vector.path("signature").asText())))
                    .isTrue();
        }
    }

    @Test
    void theReaderDecodesEverySignedVectorAndEverySignatureVerifies() {
        Ed25519Verifier verifier = new Ed25519Verifier();
        for (JsonNode vector : vectors.path("messages")) {
            SolanaTransaction transaction = SolanaTransaction.decodeBase64(
                    vector.path("signedTransactionBase64").asText());
            List<byte[]> signatures = transaction.signatures();

            assertThat(transaction.messageBytes())
                    .isEqualTo(SolanaVectors.hex(vector.path("legacyMessageHex").asText()));
            assertThat(Base58.encode(transaction.message().accountKey(0)))
                    .isEqualTo(vector.path("feePayer").asText());
            for (int i = 0; i < signatures.size(); i++) {
                assertThat(verifier.verify(
                                Base58.decode(vector.path("signers").get(i).asText()),
                                transaction.messageBytes(),
                                signatures.get(i)))
                        .isTrue();
            }
        }
    }

    @Test
    void theV0FormOfEveryVectorParsesToTheSameKeysAndInstructions() {
        for (JsonNode vector : vectors.path("messages")) {
            byte[] legacy = SolanaVectors.hex(vector.path("legacyMessageHex").asText());
            ByteArrayOutputStream v0 = new ByteArrayOutputStream();
            v0.write(0x80);
            v0.writeBytes(legacy);
            v0.write(0);

            SolanaMessage fromLegacy = SolanaMessage.parse(legacy);
            SolanaMessage fromV0 = SolanaMessage.parse(v0.toByteArray());

            assertThat(fromV0.version()).isEqualTo(SolanaMessage.Version.V0);
            assertThat(fromV0.addressTableLookups()).isEmpty();
            assertThat(fromV0.accountKeys()).containsExactlyElementsOf(fromLegacy.accountKeys());
            assertThat(fromV0.instructions()).hasSameSizeAs(fromLegacy.instructions());
            assertThat(fromV0.serialize()).isEqualTo(v0.toByteArray());
        }
    }
}
