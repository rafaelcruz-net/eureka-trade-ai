package com.memetrade.identity.domain.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.testsupport.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 10 / 12 (NFR6.3, security-design.md Q1): 60 per client IP and minute with counters of its own; the exemption
 * of the internal networks looks ONLY at the direct peer, never at the resolved client IP.
 */
@Tag("u1-legal-documents")
class LegalDocumentsRateLimiterTest {

    private static final String EXTERNAL = "203.0.113.7";
    private static final String GATEWAY = "172.28.0.1";
    private static final String WEB_CONTAINER = "172.28.1.10";

    private final MutableClock clock = new MutableClock();

    @Test
    void anExternalIpGetsTheWaitAfterItsLimitAndPassesAgainAfterTheWindow() {
        LegalDocumentsRateLimiter limiter = limiter(3, InternalNetworks.none());

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.acquire(EXTERNAL, EXTERNAL)).isEmpty();
        }
        clock.advance(Duration.ofSeconds(20));
        Optional<Duration> refused = limiter.acquire(EXTERNAL, EXTERNAL);

        assertThat(refused).contains(Duration.ofSeconds(40));
        clock.advance(Duration.ofSeconds(41));
        assertThat(limiter.acquire(EXTERNAL, EXTERNAL)).isEmpty();
    }

    @Test
    void eachClientIpHasItsOwnCounter() {
        LegalDocumentsRateLimiter limiter = limiter(1, InternalNetworks.none());

        assertThat(limiter.acquire("198.51.100.1", "198.51.100.1")).isEmpty();
        assertThat(limiter.acquire("198.51.100.2", "198.51.100.2")).isEmpty();
        assertThat(limiter.acquire("198.51.100.1", "198.51.100.1")).isPresent();
    }

    @Test
    void aDirectPeerInsideTheInternalNetworksIsNeverLimited() {
        LegalDocumentsRateLimiter limiter = limiter(1, InternalNetworks.parse(List.of("172.28.1.0/24")));

        for (int i = 0; i < 100; i++) {
            assertThat(limiter.acquire(WEB_CONTAINER, WEB_CONTAINER)).isEmpty();
        }
        assertThat(limiter.trackedKeys()).isZero();
    }

    @Test
    void aForgedInternalClientIpFromAnExternalPeerIsStillLimited() {
        LegalDocumentsRateLimiter limiter = limiter(1, InternalNetworks.parse(List.of("172.28.1.0/24")));

        // The resolved client IP claims the internal range; the direct peer is external.
        assertThat(limiter.acquire(WEB_CONTAINER, EXTERNAL)).isEmpty();
        assertThat(limiter.acquire(WEB_CONTAINER, EXTERNAL)).isPresent();
    }

    @Test
    void theBridgeGatewayOfThePublishedPortsIsNotExempt() {
        LegalDocumentsRateLimiter limiter = limiter(1, InternalNetworks.parse(List.of("172.28.1.0/24")));

        assertThat(limiter.acquire(GATEWAY, GATEWAY)).isEmpty();
        assertThat(limiter.acquire(GATEWAY, GATEWAY)).isPresent();
    }

    @Test
    void anIpv4AddressMappedInIpv6IsComparedAsIpv4() {
        InternalNetworks networks = InternalNetworks.parse(List.of("172.28.1.0/24"));

        assertThat(networks.contains("::ffff:172.28.1.10")).isTrue();
        assertThat(networks.contains("::ffff:172.28.2.10")).isFalse();
        assertThat(InternalNetworks.parse(List.of("::ffff:10.0.0.0/104")).contains("10.1.2.3"))
                .isTrue();
    }

    @Test
    void ipv6RangesAndBareAddressesAreSupported() {
        InternalNetworks networks = InternalNetworks.parse(List.of("fd00::/8", "192.0.2.5"));

        assertThat(networks.contains("fd12:3456::1")).isTrue();
        assertThat(networks.contains("[fd12:3456::1]")).isTrue();
        assertThat(networks.contains("fe80::1")).isFalse();
        assertThat(networks.contains("192.0.2.5")).isTrue();
        assertThat(networks.contains("192.0.2.6")).isFalse();
    }

    @Test
    void anythingButAnIpLiteralIsNeverInsideAndNeverResolved() {
        InternalNetworks networks = InternalNetworks.parse(List.of("0.0.0.0/0"));

        assertThat(networks.contains("localhost")).isFalse();
        assertThat(networks.contains("example.test")).isFalse();
        assertThat(networks.contains(null)).isFalse();
        assertThat(InternalNetworks.none().contains("10.0.0.1")).isFalse();
        assertThat(InternalNetworks.none().isEmpty()).isTrue();
    }

    @Test
    void anInvalidEntryStopsTheStartNamingTheSetting() {
        for (String invalid : List.of("intranet.local/24", "10.0.0.0/33", "10.0.0.0/x", "10.0.0.0/8/1", "fd00::/129")) {
            assertThatThrownBy(() -> InternalNetworks.parse(List.of(invalid)))
                    .as(invalid)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("memetrade.legal.rate-limit.internal-networks")
                    .hasMessageContaining(invalid);
        }
    }

    @Test
    void theEvictionDropsOnlyTheIdleIpsAndClearEmptiesEverything() {
        LegalDocumentsRateLimiter limiter = limiter(60, InternalNetworks.none());
        limiter.acquire("198.51.100.1", "198.51.100.1");
        clock.advance(Duration.ofSeconds(50));
        limiter.acquire("198.51.100.2", "198.51.100.2");
        clock.advance(Duration.ofSeconds(11));

        limiter.evictExpired();

        assertThat(limiter.trackedKeys()).isEqualTo(1);
        limiter.clear();
        assertThat(limiter.trackedKeys()).isZero();
    }

    @Test
    void aNonPositiveLimitIsRefused() {
        assertThatThrownBy(() -> limiter(0, InternalNetworks.none())).isInstanceOf(IllegalArgumentException.class);
        assertThat(limiter(60, InternalNetworks.none()).limit()).isEqualTo(60);
    }

    private LegalDocumentsRateLimiter limiter(int limit, InternalNetworks networks) {
        return new LegalDocumentsRateLimiter(limit, clock, networks);
    }
}
