package com.memetrade.identity.domain.signature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.testsupport.SyntheticIds;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u1")
class Base58Test {

    @Test
    void decodesKnownBitcoinAlphabetVectors() {
        assertThat(Base58.decode("")).isEmpty();
        assertThat(Base58.decode("2g")).isEqualTo("a".getBytes(StandardCharsets.US_ASCII));
        assertThat(Base58.decode("a3gV")).isEqualTo("bbb".getBytes(StandardCharsets.US_ASCII));
        assertThat(Base58.decode("StV1DL6CwTryKyV")).isEqualTo("hello world".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void leadingOnesBecomeLeadingZeroBytes() {
        assertThat(Base58.decode("1")).containsExactly(0);
        assertThat(Base58.decode("11")).containsExactly(0, 0);
        assertThat(Base58.decode("1112")).containsExactly(0, 0, 0, 1);
    }

    @Test
    void refusesCharactersOutsideTheAlphabet() {
        for (String invalid : new String[] {"0", "O", "I", "l", "abc+", "é"}) {
            assertThatThrownBy(() -> Base58.decode(invalid))
                    .as(invalid)
                    .isInstanceOf(Base58.InvalidEncodingException.class);
        }
        assertThatThrownBy(() -> Base58.decode(null)).isInstanceOf(Base58.InvalidEncodingException.class);
    }

    @Test
    void decodeExactRefusesUnexpectedLengths() {
        String thirtyTwoBytes = SyntheticIds.mint();

        assertThat(Base58.decodeExact(thirtyTwoBytes, 32)).hasSize(32);
        assertThatThrownBy(() -> Base58.decodeExact(thirtyTwoBytes, 64))
                .isInstanceOf(Base58.InvalidEncodingException.class)
                .hasMessageNotContaining(thirtyTwoBytes);
        assertThatThrownBy(() -> Base58.decodeExact("2g", 32)).isInstanceOf(Base58.InvalidEncodingException.class);
    }

    @Test
    void roundTripsRandomPayloadsIncludingLeadingZeros() {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 200; i++) {
            byte[] bytes = new byte[1 + random.nextInt(64)];
            random.nextBytes(bytes);
            if (i % 5 == 0) {
                bytes[0] = 0;
            }
            assertThat(Base58.decode(SyntheticIds.encode(bytes))).isEqualTo(bytes);
        }
    }
}
