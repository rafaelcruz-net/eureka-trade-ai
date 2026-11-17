package com.memetrade.botwallet.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * BR1.3 of the U1 and BR1.1 of the U5: a release in force on the network of the environment. Since the U5 only an
 * {@code ACTIVE} release is in force; {@code QUARANTINE}, {@code BLOCKED} and {@code REVOKED} never are.
 */
public final class RealReleases {

    /** 261113-trading-real-bot U6: the state shown when the person has no release on the network. */
    public static final String NONE = "NONE";

    private RealReleases() {}

    public static boolean isEffective(Collection<RealRelease> releases, Network network, Instant now) {
        return releases.stream().anyMatch(release -> release.isEffectiveAt(network, now));
    }

    /**
     * 261113-trading-real-bot U6 (rules.md "Mudança de contrato" item 1, BR2.5): the release the screen shows for the
     * network of the environment. The open release (QUARANTINE, ACTIVE or BLOCKED; at most one per person and network)
     * wins; without one, the most recently requested REVOKED; a release of another network never shows.
     */
    public static Current current(Collection<RealRelease> releases, Network network) {
        Optional<RealRelease> chosen = releases.stream()
                .filter(release -> release.network() == network)
                .max(Comparator.comparing(RealRelease::notRevoked).thenComparing(RealRelease::requestedAt));
        return chosen.map(release -> new Current(
                        release.state().name(),
                        release.effectiveAt(),
                        release.blockedReason() == null
                                ? null
                                : release.blockedReason().name()))
                .orElse(new Current(NONE, null, null));
    }

    /**
     * The shown release: {@code state} is one of {@link ReleaseState} or {@link #NONE}; {@code effectiveAt} is the end
     * of the quarantine; {@code blockedReason} the first red gate of a BLOCKED release.
     */
    public record Current(String state, Instant effectiveAt, String blockedReason) {}
}
