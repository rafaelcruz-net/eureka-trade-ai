package com.memetrade.botwallet.domain;

import java.util.Optional;

/**
 * 261113-trading-real-bot U5 (BR1.2, BR1.3, S5 to S7): who may be released. In this order: an ACTIVE bot wallet, the
 * acceptance of the terms v3, no release still open on the network; then, on the MAINNET only, the founder first (no
 * minimum wait, Q2) and at most 20 guests besides the founder. On the DEVNET and the LOCALNET neither of the two last
 * rules applies.
 */
public final class ReleaseRules {

    /** BR1.3, team.md "Rollout do REAL": up to 20 guests besides the founder on the MAINNET. */
    public static final int MAX_GUESTS = 20;

    private ReleaseRules() {}

    public static Optional<ReleaseRefusal> refusal(ReleaseFacts facts) {
        if (!facts.activeBotWallet()) {
            return Optional.of(ReleaseRefusal.NO_BOT_WALLET);
        }
        if (!facts.termsV3Accepted()) {
            return Optional.of(ReleaseRefusal.TERMS_V3_MISSING);
        }
        if (facts.openRelease()) {
            return Optional.of(ReleaseRefusal.RELEASE_EXISTS);
        }
        if (facts.network() != Network.MAINNET || facts.founder()) {
            return Optional.empty();
        }
        if (!facts.founderActive()) {
            return Optional.of(ReleaseRefusal.FOUNDER_FIRST);
        }
        if (facts.openGuests() >= MAX_GUESTS) {
            return Optional.of(ReleaseRefusal.PILOT_FULL);
        }
        return Optional.empty();
    }
}
