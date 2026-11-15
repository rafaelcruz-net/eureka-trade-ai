package com.memetrade.shared.solana;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 3 of the U4 (logical-components.md, deviation of Q2): the api and the TxSigner read the SAME vectors. The two
 * versioned files must have the same SHA-256; the TxSigner has the mirror of this test.
 */
@Tag("u4-real-execution")
class SolanaVectorFilesTest {

    @Test
    void theVectorsOfTheApiAndOfTheTxSignerAreTheSameFile() throws Exception {
        Path api = SolanaVectorsGenerationTest.API_FILE;
        Path txsigner = SolanaVectorsGenerationTest.TXSIGNER_FILE;

        assertThat(api).exists();
        assertThat(txsigner).exists();
        assertThat(sha256(txsigner)).isEqualTo(sha256(api));
    }

    @Test
    void theVectorsHaveEveryKindTheTwoAppsCheck() {
        var vectors = SolanaVectors.load();

        assertThat(vectors.path("base58").size()).isGreaterThanOrEqualTo(5);
        assertThat(vectors.path("ata").size()).isGreaterThanOrEqualTo(6);
        assertThat(vectors.path("signatures").size()).isGreaterThanOrEqualTo(3);
        assertThat(SolanaVectors.messages())
                .extracting(SolanaVectors.MessageVector::name)
                .contains("auto-full-buy", "signed-buy", "auto-full-sell", "signed-sell");
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
