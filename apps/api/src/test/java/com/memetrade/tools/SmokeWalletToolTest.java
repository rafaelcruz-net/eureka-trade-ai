package com.memetrade.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.identity.domain.signature.Ed25519SignatureVerifier;
import com.memetrade.testsupport.SyntheticIds;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("u1")
class SmokeWalletToolTest {

    @TempDir
    Path dir;

    @Test
    void theSeedSignsMessagesThatTheApiVerifiesForThePrintedAddress() throws Exception {
        Path file = dir.resolve("wallet.json");

        String address = SmokeWalletTool.generate(file);

        Matcher seed = Pattern.compile("\"seed\":\"([^\"]+)\"").matcher(Files.readString(file));
        assertThat(seed.find()).isTrue();
        // Same PKCS#8 wrapping the Playwright test wallet uses in Node to sign with the seed.
        byte[] pkcs8 = HexFormat.of()
                .parseHex("302e020100300506032b657004220420"
                        + HexFormat.of().formatHex(Base64.getDecoder().decode(seed.group(1))));
        PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update("smoke".getBytes(StandardCharsets.UTF_8));

        assertThat(new Ed25519SignatureVerifier().verify(address, "smoke", SyntheticIds.encode(signer.sign())))
                .isEqualTo(Ed25519SignatureVerifier.Result.VALID);
    }

    @Test
    void neverOverwritesAnExistingFileAndRequiresOneArgument() throws Exception {
        Path file = Files.writeString(dir.resolve("existing.json"), "{}");

        assertThatThrownBy(() -> SmokeWalletTool.generate(file))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThatThrownBy(() -> SmokeWalletTool.main(new String[0])).isInstanceOf(IllegalArgumentException.class);
    }
}
