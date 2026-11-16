package com.memetrade.botwallet.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 4 of the U5, written BEFORE the code (BR1.1 to BR1.3, S1, S2, S5, S6, S7): who may be released, and that only an
 * ACTIVE release is in force.
 */
@Tag("u5-real-operations")
class ReleaseRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");

    private static ReleaseFacts guest(Network network, boolean founderActive, int guests) {
        return new ReleaseFacts(network, true, true, false, false, founderActive, guests);
    }

    @Test
    void aGuestIsReleasedOnTheMainnetOnlyAfterTheFounderAndUpToTwenty() {
        // S5: no active founder.
        assertThat(ReleaseRules.refusal(guest(Network.MAINNET, false, 0))).contains(ReleaseRefusal.FOUNDER_FIRST);
        // S6: founder active for one hour, no wait of 7 days.
        assertThat(ReleaseRules.refusal(guest(Network.MAINNET, true, 0))).isEmpty();
        assertThat(ReleaseRules.refusal(guest(Network.MAINNET, true, 19))).isEmpty();
        // S7: the 21st person (the founder plus 20 guests).
        assertThat(ReleaseRules.refusal(guest(Network.MAINNET, true, 20))).contains(ReleaseRefusal.PILOT_FULL);
    }

    @Test
    void theFounderIsReleasedFirstAndNeverCountsAsAGuest() {
        ReleaseFacts founder = new ReleaseFacts(Network.MAINNET, true, true, false, true, false, 20);

        assertThat(ReleaseRules.refusal(founder)).isEmpty();
    }

    @Test
    void outsideTheMainnetNeitherTheFounderNorTheLimitApply() {
        assertThat(ReleaseRules.refusal(guest(Network.DEVNET, false, 50))).isEmpty();
        assertThat(ReleaseRules.refusal(guest(Network.LOCALNET, false, 50))).isEmpty();
    }

    @Test
    void theWalletTheTermsAndAnOpenReleaseComeFirstInThisOrder() {
        assertThat(ReleaseRules.refusal(new ReleaseFacts(Network.MAINNET, false, false, true, false, false, 20)))
                .contains(ReleaseRefusal.NO_BOT_WALLET);
        assertThat(ReleaseRules.refusal(new ReleaseFacts(Network.MAINNET, true, false, true, false, false, 20)))
                .contains(ReleaseRefusal.TERMS_V3_MISSING);
        assertThat(ReleaseRules.refusal(new ReleaseFacts(Network.MAINNET, true, true, true, false, false, 20)))
                .contains(ReleaseRefusal.RELEASE_EXISTS);
    }

    @Test
    void eachRefusalHasItsStatus() {
        assertThat(ReleaseRefusal.FOUNDER_FIRST.status()).isEqualTo(409);
        assertThat(ReleaseRefusal.PILOT_FULL.status()).isEqualTo(409);
        assertThat(ReleaseRefusal.RELEASE_EXISTS.status()).isEqualTo(409);
        assertThat(ReleaseRefusal.NO_BOT_WALLET.status()).isEqualTo(422);
        assertThat(ReleaseRefusal.TERMS_V3_MISSING.status()).isEqualTo(422);
    }

    /** S2, S3, BR1.1: only ACTIVE is in force; QUARANTINE, BLOCKED and REVOKED never are, even past the 24 hours. */
    @Test
    void onlyAnActiveReleaseOnTheNetworkOfTheEnvironmentIsInForce() {
        UUID user = UUID.randomUUID();
        RealRelease requested = RealRelease.requested(user, Network.DEVNET, UUID.randomUUID(), NOW);
        RealRelease active = withState(requested, ReleaseState.ACTIVE);
        RealRelease blocked = withState(requested, ReleaseState.BLOCKED);
        Instant later = NOW.plus(Duration.ofDays(3));

        assertThat(requested.state()).isEqualTo(ReleaseState.QUARANTINE);
        assertThat(requested.effectiveAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(RealReleases.isEffective(List.of(requested), Network.DEVNET, later))
                .isFalse();
        assertThat(RealReleases.isEffective(List.of(blocked), Network.DEVNET, later))
                .isFalse();
        assertThat(RealReleases.isEffective(List.of(active), Network.DEVNET, later))
                .isTrue();
        assertThat(RealReleases.isEffective(List.of(active), Network.MAINNET, later))
                .isFalse();
        assertThat(RealReleases.isEffective(List.of(active.revoked(later, user)), Network.DEVNET, later))
                .isFalse();
    }

    @Test
    void theGatesDecideOnlyAfterTheQuarantineAndAgainForABlockedRelease() {
        RealRelease requested = RealRelease.requested(UUID.randomUUID(), Network.DEVNET, UUID.randomUUID(), NOW);

        assertThat(requested.awaitsGates(NOW.plus(Duration.ofHours(23)))).isFalse();
        assertThat(requested.awaitsGates(NOW.plus(Duration.ofHours(24)))).isTrue();
        assertThat(withState(requested, ReleaseState.BLOCKED).awaitsGates(NOW.plus(Duration.ofDays(2))))
                .isTrue();
        assertThat(withState(requested, ReleaseState.ACTIVE).awaitsGates(NOW.plus(Duration.ofDays(2))))
                .isFalse();
        assertThat(requested.revoked(NOW, null).notRevoked()).isFalse();
    }

    private static RealRelease withState(RealRelease release, ReleaseState state) {
        return new RealRelease(
                release.releaseId(),
                release.userId(),
                release.network(),
                release.requestedBy(),
                release.requestedAt(),
                release.effectiveAt(),
                null,
                state,
                state == ReleaseState.BLOCKED ? BlockedReason.CI_RED : null,
                null,
                null);
    }
}
