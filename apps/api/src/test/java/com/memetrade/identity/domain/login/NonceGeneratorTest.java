package com.memetrade.identity.domain.login;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u1")
class NonceGeneratorTest {

    private final NonceGenerator generator = new NonceGenerator(new SecureRandom());

    @Test
    void everyNonceCarries128BitsOfRandomness() {
        String nonce = generator.next();

        assertThat(Base64.getUrlDecoder().decode(nonce)).hasSize(16);
        assertThat(nonce).hasSize(22).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void noncesDoNotRepeatAndCoverTheAlphabet() {
        Set<String> seen = new HashSet<>();
        Set<Character> characters = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            String nonce = generator.next();
            assertThat(seen.add(nonce)).isTrue();
            nonce.chars().forEach(c -> characters.add((char) c));
        }
        // 5,000 x 22 random Base64url characters reach essentially all 64 symbols; a weak source would not.
        assertThat(characters.size()).isGreaterThanOrEqualTo(60);
    }
}
