package com.memetrade.txsigner.proof;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.txsigner.codec.SolanaMessage;
import com.memetrade.txsigner.codec.SolanaTransaction;
import com.memetrade.txsigner.domain.kms.KeyTags;
import com.memetrade.txsigner.policy.Ed25519Verifier;
import com.memetrade.txsigner.testsupport.JupiterSwapFixture;
import com.memetrade.txsigner.testsupport.KmsSimulator;
import com.memetrade.txsigner.testsupport.TxBytes;
import java.security.MessageDigest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Technical proof 2 of the B1 (code-generation-plan.md Step 4, TR-C03, TR-C05): a v0 swap transaction with address
 * lookup tables, in the shape the Jupiter API returns, is decoded by the OWN decoder (no Solana SDK), keeps its bytes,
 * receives the KMS signature in the slot of the bot wallet and verifies. The run on the local validator with cloned
 * accounts is recorded as not executed in docs/proofs/jupiter-tx.md.
 */
class JupiterTxProofTest {

    private final KmsSimulator kms = new KmsSimulator();
    private String keyRef;
    private byte[] bot;
    private byte[] message;

    @BeforeEach
    void prepare() {
        keyRef = kms.createKey(new KeyTags("ab", UUID.randomUUID().toString(), "LOCALNET"));
        bot = kms.publicKey(keyRef);
        message = JupiterSwapFixture.message(bot);
    }

    @Test
    void theOwnDecoderReadsTheV0SwapWithItsAddressTables() {
        SolanaTransaction tx = SolanaTransaction.decode(TxBytes.unsigned(message, 1));

        SolanaMessage decoded = tx.message();
        assertThat(decoded.version()).isEqualTo(SolanaMessage.Version.V0);
        assertThat(decoded.header().requiredSignatures()).isEqualTo(1);
        assertThat(decoded.accountKeys()).hasSize(8);
        assertThat(decoded.accountKeys().get(0)).isEqualTo(bot);
        assertThat(decoded.addressTableLookups()).hasSize(2);
        assertThat(decoded.addressTableLookups().get(0).writableIndexes()).containsExactly(4, 7);
        assertThat(decoded.addressTableLookups().get(1).readonlyIndexes()).containsExactly(9, 15);
        assertThat(decoded.instructions()).hasSize(4);
        assertThat(decoded.programId(decoded.instructions().get(3))).isEqualTo(JupiterSwapFixture.JUPITER_PROGRAM);
        assertThat(decoded.instructions().get(3).data()).hasSize(JupiterSwapFixture.ROUTE_DATA_LENGTH);
        assertThat(decoded.totalAccountCount()).isEqualTo(8 + 3 + 4);
    }

    @Test
    void theDecodedMessageSerializesBackToTheSameBytes() {
        SolanaMessage decoded = SolanaMessage.parse(message);

        assertThat(decoded.serialize()).isEqualTo(message);
    }

    @Test
    void theKmsSignatureGoesInTheSlotOfTheBotWalletAndVerifies() throws Exception {
        SolanaTransaction unsigned = SolanaTransaction.decode(TxBytes.unsigned(message, 1));
        byte[] digestBefore = MessageDigest.getInstance("SHA-256").digest(unsigned.messageBytes());

        byte[] signature = kms.sign(keyRef, unsigned.messageBytes());
        SolanaTransaction signed = unsigned.withSignature(0, signature);
        byte[] wire = signed.serialize();

        assertThat(wire).isEqualTo(TxBytes.transaction(message, signature));
        assertThat(MessageDigest.getInstance("SHA-256").digest(signed.messageBytes()))
                .isEqualTo(digestBefore);
        assertThat(new Ed25519Verifier()
                        .verify(bot, signed.messageBytes(), signed.signatures().get(0)))
                .isTrue();
    }

    @Test
    void theSignedSwapFitsInOnePacket() {
        byte[] wire = TxBytes.transaction(message, new byte[64]);

        assertThat(wire.length).isLessThanOrEqualTo(SolanaTransaction.MAX_TRANSACTION_SIZE);
        assertThat(SolanaTransaction.decode(wire).serialize()).isEqualTo(wire);
    }
}
