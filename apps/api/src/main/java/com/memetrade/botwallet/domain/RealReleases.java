package com.memetrade.botwallet.domain;

import java.time.Instant;
import java.util.Collection;

/** BR1.3: a release in force on the network of the environment, effective and not revoked. */
public final class RealReleases {

    private RealReleases() {}

    public static boolean isEffective(Collection<RealRelease> releases, Network network, Instant now) {
        return releases.stream().anyMatch(release -> release.isEffectiveAt(network, now));
    }
}
