package com.memetrade.botwallet.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 261113-trading-real-bot U6, Step 3, written before the code (rules.md "Mudança de contrato" item 1, BR2.5): the
 * release the screen shows for one person on the network of the environment. The open release (QUARANTINE, ACTIVE or
 * BLOCKED; at most one per person and network) wins; without one, the most recent REVOKED; a release of another network
 * never shows; none at all is NONE.
 */
class RealReleaseCurrentTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final UUID USER = UUID.randomUUID();

    private static RealRelease release(Network network, ReleaseState state, Instant requestedAt) {
        Instant revokedAt = state == ReleaseState.REVOKED ? requestedAt.plus(Duration.ofHours(1)) : null;
        return new RealRelease(
                UUID.randomUUID(),
                USER,
                network,
                USER,
                requestedAt,
                requestedAt.plus(RealRelease.QUARANTINE),
                revokedAt,
                state,
                state == ReleaseState.BLOCKED ? BlockedReason.CI_RED : null,
                null,
                null);
    }

    @Test
    void withoutAnyReleaseTheStateIsNone() {
        RealReleases.Current current = RealReleases.current(List.of(), Network.LOCALNET);

        assertThat(current.state()).isEqualTo("NONE");
        assertThat(current.effectiveAt()).isNull();
        assertThat(current.blockedReason()).isNull();
    }

    @Test
    void theOpenReleaseOfTheNetworkWinsOverAnOlderRevokedOne() {
        RealRelease revoked = release(Network.LOCALNET, ReleaseState.REVOKED, NOW.minus(Duration.ofDays(10)));
        RealRelease quarantine = release(Network.LOCALNET, ReleaseState.QUARANTINE, NOW.minus(Duration.ofHours(2)));

        RealReleases.Current current = RealReleases.current(List.of(revoked, quarantine), Network.LOCALNET);

        assertThat(current.state()).isEqualTo("QUARANTINE");
        assertThat(current.effectiveAt()).isEqualTo(quarantine.effectiveAt());
    }

    @Test
    void aBlockedReleaseCarriesItsFirstRedGate() {
        RealRelease blocked = release(Network.LOCALNET, ReleaseState.BLOCKED, NOW.minus(Duration.ofDays(2)));

        RealReleases.Current current = RealReleases.current(List.of(blocked), Network.LOCALNET);

        assertThat(current.state()).isEqualTo("BLOCKED");
        assertThat(current.blockedReason()).isEqualTo("CI_RED");
    }

    @Test
    void anActiveReleaseIsActive() {
        RealRelease active = release(Network.LOCALNET, ReleaseState.ACTIVE, NOW.minus(Duration.ofDays(2)));

        assertThat(RealReleases.current(List.of(active), Network.LOCALNET).state())
                .isEqualTo("ACTIVE");
    }

    @Test
    void withOnlyRevokedReleasesTheMostRecentOneShows() {
        RealRelease older = release(Network.LOCALNET, ReleaseState.REVOKED, NOW.minus(Duration.ofDays(10)));
        RealRelease newer = release(Network.LOCALNET, ReleaseState.REVOKED, NOW.minus(Duration.ofDays(3)));

        RealReleases.Current current = RealReleases.current(List.of(older, newer), Network.LOCALNET);

        assertThat(current.state()).isEqualTo("REVOKED");
        assertThat(current.effectiveAt()).isEqualTo(newer.effectiveAt());
    }

    @Test
    void aReleaseOfAnotherNetworkNeverShows() {
        RealRelease mainnet = release(Network.MAINNET, ReleaseState.ACTIVE, NOW.minus(Duration.ofDays(2)));

        assertThat(RealReleases.current(List.of(mainnet), Network.LOCALNET).state())
                .isEqualTo("NONE");
    }
}
