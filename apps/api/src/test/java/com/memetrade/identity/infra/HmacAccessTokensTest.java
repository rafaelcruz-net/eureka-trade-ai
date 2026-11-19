package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.identity.domain.session.AccessTokens.Claims;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.TestWallet;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u1")
class HmacAccessTokensTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

    private final HmacAccessTokens tokens = new HmacAccessTokens(AbstractPostgresIT.TEST_SIGNING_KEY);
    private final Claims claims =
            new Claims(UUID.randomUUID(), UUID.randomUUID(), NOW, NOW.plus(Duration.ofMinutes(15)));

    @Test
    void issuedTokensVerifyWithTheirSessionAndUser() {
        assertThat(tokens.verify(tokens.issue(claims), NOW.plusSeconds(60))).contains(claims);
    }

    @Test
    void tokensExpireAtTheirExpiryInstant() {
        String token = tokens.issue(claims);

        assertThat(tokens.verify(token, NOW.plus(Duration.ofMinutes(15)).minusSeconds(1)))
                .isPresent();
        assertThat(tokens.verify(token, NOW.plus(Duration.ofMinutes(15)))).isEmpty();
    }

    @Test
    void aTamperedTokenOrOneSignedWithAnotherKeyIsRefused() {
        String token = tokens.issue(claims);
        // The first character of the signature carries six significant bits; the last one of a base64url HMAC-SHA256
        // only four, so changing the end could leave the decoded signature unchanged (regression of CI run
        // 44451366746).
        int signatureStart = token.lastIndexOf('.') + 1;
        char first = token.charAt(signatureStart);
        String tampered =
                token.substring(0, signatureStart) + (first == 'A' ? 'B' : 'A') + token.substring(signatureStart + 1);
        HmacAccessTokens otherKey = new HmacAccessTokens(Base64.getEncoder().encodeToString(new byte[48]));

        assertThat(tokens.verify(tampered, NOW)).isEmpty();
        assertThat(otherKey.verify(token, NOW)).isEmpty();
    }

    @Test
    void aLoginSignatureIsNeverAcceptedAsAToken() {
        TestWallet wallet = TestWallet.generate();

        assertThat(tokens.verify(wallet.sign("login message"), NOW)).isEmpty();
        assertThat(tokens.verify("", NOW)).isEmpty();
        assertThat(tokens.verify(null, NOW)).isEmpty();
    }

    @Test
    void anUnsignedAlgNoneTokenIsRefused() {
        String[] parts = tokens.issue(claims).split("\\.");
        String noneHeader = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());

        assertThat(tokens.verify(noneHeader + "." + parts[1] + ".", NOW)).isEmpty();
    }

    @Test
    void weakOrMissingSigningKeysAreRefusedAtStartup() {
        assertThatThrownBy(() -> new HmacAccessTokens("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new HmacAccessTokens(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new HmacAccessTokens("not base64 !!")).isInstanceOf(IllegalStateException.class);
    }
}
