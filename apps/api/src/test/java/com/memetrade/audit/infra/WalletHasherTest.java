package com.memetrade.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.testsupport.TestWallet;
import com.memetrade.testsupport.TestWalletHmac;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.SanitizableData;

/**
 * Passo 5 of u2-retention, written before the code (BR2.1, BR2.2, BR2.4, NFR1.1, NFR6.1, NFR3.3): the HMAC of a wallet
 * address in the audit trail. The same wallet always gives the same lower-case hex HMAC of 64 characters (the
 * standard HMAC-SHA-256 of the address with the key); different wallets give different ones; a key shorter than 32
 * bytes in UTF-8, or none, is refused naming the property and never the value; the key never leaves the hasher.
 */
@Tag("u2-retention")
class WalletHasherTest {

    private static final String KEY = "unit-test-wallet-hmac-key-not-secret-0123";

    private final WalletHasher hasher = new WalletHasher(KEY);

    @Test
    void theSameWalletAlwaysGivesTheSameHmac() {
        String address = TestWallet.generate().address();

        assertThat(hasher.hmac(address)).isEqualTo(hasher.hmac(address));
        assertThat(new WalletHasher(KEY).hmac(address)).isEqualTo(hasher.hmac(address));
    }

    @Test
    void theHmacIsTheStandardHmacSha256OfTheAddressWithTheKey() {
        String address = TestWallet.generate().address();

        assertThat(hasher.hmac(address)).isEqualTo(TestWalletHmac.of(KEY, address));
    }

    @Test
    void differentWalletsGiveDifferentHmacs() {
        Set<String> hmacs = IntStream.range(0, 50)
                .mapToObj(i -> hasher.hmac(TestWallet.generate().address()))
                .collect(Collectors.toSet());

        assertThat(hmacs).hasSize(50);
    }

    @Test
    void anotherKeyGivesAnotherHmacForTheSameWallet() {
        String address = TestWallet.generate().address();

        assertThat(new WalletHasher(KEY + "-other").hmac(address)).isNotEqualTo(hasher.hmac(address));
    }

    @Test
    void theHmacIsLowerCaseHexOf64Characters() {
        assertThat(hasher.hmac(TestWallet.generate().address())).matches("^[0-9a-f]{64}$");
    }

    @Test
    void aKeyShorterThan32BytesIsRefusedNamingThePropertyNeverTheValue() {
        String shortKey = "x".repeat(31);

        assertThatThrownBy(() -> new WalletHasher(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("memetrade.audit.wallet-hmac-key")
                .hasMessageContaining("MEMETRADE_AUDIT_WALLET_HMAC_KEY")
                .hasMessageContaining("32 bytes")
                .hasMessageNotContaining(shortKey);
    }

    @Test
    void aMissingOrBlankKeyIsRefused() {
        assertThatThrownBy(() -> new WalletHasher(null)).hasMessageContaining("memetrade.audit.wallet-hmac-key");
        assertThatThrownBy(() -> new WalletHasher("   ")).hasMessageContaining("memetrade.audit.wallet-hmac-key");
    }

    @Test
    void theKeyLengthCountsUtf8Bytes() {
        String sixteenTwoByteCharacters = "éééééééééééééééé";

        WalletHasher accepted = new WalletHasher(sixteenTwoByteCharacters);

        assertThat(accepted.hmac(TestWallet.generate().address())).hasSize(64);
        assertThatThrownBy(() -> new WalletHasher("ééééééééééééééé")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void textThatIsNotAWalletIsNeverHashed() {
        assertThatThrownBy(() -> hasher.hmac("not a wallet"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("not a wallet");
        assertThatThrownBy(() -> hasher.hmac(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theHasherIsSafeAcrossThreads() throws Exception {
        String address = TestWallet.generate().address();
        String expected = hasher.hmac(address);
        Callable<String> task = () -> hasher.hmac(address);

        List<String> results = com.memetrade.testsupport.Concurrently.run(task, task, task, task, task, task).stream()
                .map(com.memetrade.testsupport.Concurrently.Outcome::value)
                .toList();

        assertThat(results).containsOnly(expected);
    }

    @Test
    void theKeyNeverLeavesTheHasher() {
        assertThat(hasher.toString()).doesNotContain(KEY);
    }

    @Test
    void theActuatorSanitizesTheKeyByPropertyAndByVariableName() {
        WalletHmacKeySanitizer sanitizer = new WalletHmacKeySanitizer();

        assertThat(sanitizer.apply(data("memetrade.audit.wallet-hmac-key", KEY)).getValue())
                .isEqualTo(SanitizableData.SANITIZED_VALUE);
        assertThat(sanitizer.apply(data("MEMETRADE_AUDIT_WALLET_HMAC_KEY", KEY)).getValue())
                .isEqualTo(SanitizableData.SANITIZED_VALUE);
        assertThat(sanitizer.apply(data("memetrade.audit.relay-interval", "1s")).getValue())
                .isEqualTo("1s");
    }

    private static SanitizableData data(String key, String value) {
        return new SanitizableData(null, key, value);
    }
}
