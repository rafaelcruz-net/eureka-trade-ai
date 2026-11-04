package com.memetrade.identity.domain.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Passo 5 (BR1.1, BR2.3, NFR6.1, NFR6.2, NFR6.7): entropy, alphabet, digest, suffix and format of the code. */
@Tag("u3")
class InviteCodesTest {

    /** 32 zero bytes encode to 43 'A'; its SHA-256 was computed outside the code (sha256sum). */
    private static final String ZERO_CODE = "A".repeat(43);

    private static final String ZERO_DIGEST = "0f007385b6f9d4b7eeb2748605afe1a984a0a3bfa3f014d09e2a784ce9e5cd1a";

    @Test
    void tenThousandCodesAreUniqueWith43CharactersOfTheBase64UrlAlphabet() {
        InviteCodes codes = new InviteCodes(new SecureRandom());
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            String code = codes.newCode();
            assertThat(code).hasSize(43).matches("[A-Za-z0-9_-]{43}");
            seen.add(code);
        }

        assertThat(seen).hasSize(10_000);
    }

    @Test
    void theCodeIsTheBase64UrlEncodingOf32RandomBytesWithoutPadding() {
        InviteCodes fixed = new InviteCodes(bytes -> {
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) i;
            }
        });

        assertThat(fixed.newCode()).isEqualTo("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8");
        assertThat(new InviteCodes(bytes -> {}).newCode()).isEqualTo(ZERO_CODE);
    }

    @Test
    void theDigestIsTheKnownSha256HexOfTheCodeAndTheSuffixItsLastFourCharacters() {
        assertThat(InviteCodes.digest(ZERO_CODE)).isEqualTo(ZERO_DIGEST);
        assertThat(InviteCodes.digest("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"))
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(ZERO_DIGEST);
        assertThat(InviteCodes.suffix("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"))
                .isEqualTo("dHh8");
        assertThat(InviteCodes.suffix(ZERO_CODE)).isEqualTo("AAAA");
    }

    @Test
    void wellFormedMeans43CharactersOfTheAlphabetOnly() {
        assertThat(InviteCodes.isWellFormed(ZERO_CODE)).isTrue();
        assertThat(InviteCodes.isWellFormed("abc-_DEF0123456789abcdefghijklmnopqrstuvwxy"))
                .isTrue();
        assertThat(InviteCodes.isWellFormed("A".repeat(42))).isFalse();
        assertThat(InviteCodes.isWellFormed("A".repeat(44))).isFalse();
        assertThat(InviteCodes.isWellFormed("A".repeat(42) + "+")).isFalse();
        assertThat(InviteCodes.isWellFormed("A".repeat(42) + "/")).isFalse();
        assertThat(InviteCodes.isWellFormed("A".repeat(42) + "=")).isFalse();
        assertThat(InviteCodes.isWellFormed("A".repeat(42) + " ")).isFalse();
        assertThat(InviteCodes.isWellFormed("")).isFalse();
        assertThat(InviteCodes.isWellFormed(null)).isFalse();
    }

    @Test
    void digestAndSuffixRefuseAMalformedCodeWithoutEchoingIt() {
        String secretLooking = "B".repeat(44);

        assertThatThrownBy(() -> InviteCodes.digest(secretLooking))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(secretLooking);
        assertThatThrownBy(() -> InviteCodes.suffix(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
