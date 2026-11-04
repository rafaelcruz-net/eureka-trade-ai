package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** U3 Passo 1 (NFR6.6, NFR2.3): range of the invitation lookup limit and the terms location, checked at start-up. */
@Tag("u3")
class IdentityPropertiesTest {

    @Test
    void theInvitationLookupLimitDefaultsToTenAndAcceptsTheLoadtestValue() {
        assertThat(properties(null).inviteLookupRateLimitPerMinute()).isEqualTo(10);
        assertThat(properties(1000).inviteLookupRateLimitPerMinute()).isEqualTo(1000);
        assertThat(properties(1).inviteLookupRateLimitPerMinute()).isEqualTo(1);
        assertThat(properties(100_000).inviteLookupRateLimitPerMinute()).isEqualTo(100_000);
    }

    @Test
    void aLimitOutOfRangeStopsTheStartWithAMessageNamingTheSetting() {
        assertThatThrownBy(() -> properties(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invite-lookup-rate-limit-per-minute")
                .hasMessageContaining("INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE");
        assertThatThrownBy(() -> properties(-5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(100_001)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTermsLocationDefaultsToTheClasspathFolderAndAlwaysEndsWithASlash() {
        assertThat(new TermsProperties(null).location()).isEqualTo("classpath:terms/");
        assertThat(new TermsProperties("  ").location()).isEqualTo("classpath:terms/");
        assertThat(new TermsProperties("file:/tmp/terms").location()).isEqualTo("file:/tmp/terms/");
        assertThat(new TermsProperties("classpath:other/").location()).isEqualTo("classpath:other/");
    }

    private static IdentityProperties properties(Integer inviteLimit) {
        return new IdentityProperties(
                "key",
                URI.create("http://localhost:3000"),
                List.of(" a ", ""),
                "solana:mainnet",
                Duration.ofMinutes(5),
                Duration.ofMinutes(15),
                Duration.ofHours(8),
                Duration.ofHours(12),
                10,
                inviteLimit,
                null);
    }
}
