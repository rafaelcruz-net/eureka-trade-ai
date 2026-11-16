package com.memetrade.botwallet.domain;

import java.time.Instant;
import java.util.Collection;

/**
 * BR1.3 of the U1 and BR1.1 of the U5: a release in force on the network of the environment. Since the U5 only an
 * {@code ACTIVE} release is in force; {@code QUARANTINE}, {@code BLOCKED} and {@code REVOKED} never are.
 */
public final class RealReleases {

    private RealReleases() {}

    public static boolean isEffective(Collection<RealRelease> releases, Network network, Instant now) {
        return releases.stream().anyMatch(release -> release.isEffectiveAt(network, now));
    }
}
