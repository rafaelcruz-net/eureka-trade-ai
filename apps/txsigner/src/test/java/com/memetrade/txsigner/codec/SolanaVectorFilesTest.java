package com.memetrade.txsigner.codec;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.txsigner.testsupport.SolanaVectors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Step 3 of the U4 (deviation of Q2): the TxSigner and the api read the SAME vectors file (mirror of the api test). */
class SolanaVectorFilesTest {

    @Test
    void theVectorsOfTheTxSignerAndOfTheApiAreTheSameFile() throws Exception {
        assertThat(SolanaVectors.FILE).exists();
        assertThat(SolanaVectors.API_FILE).exists();
        assertThat(sha256(SolanaVectors.FILE)).isEqualTo(sha256(SolanaVectors.API_FILE));
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
