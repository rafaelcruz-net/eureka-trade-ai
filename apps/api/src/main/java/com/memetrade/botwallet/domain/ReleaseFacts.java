package com.memetrade.botwallet.domain;

/**
 * 261113-trading-real-bot U5 (BR1.2, BR1.3): what the release of one person reads, under the lock of the MAINNET.
 *
 * @param network the network of the environment (the release is always on it)
 * @param openRelease the person already has a release not revoked on that network
 * @param founder the person is the founder ({@code memetrade.real.founder-user-id})
 * @param founderActive the founder has an ACTIVE release on the MAINNET
 * @param openGuests the releases not revoked on the MAINNET of every person but the founder
 */
public record ReleaseFacts(
        Network network,
        boolean activeBotWallet,
        boolean termsV3Accepted,
        boolean openRelease,
        boolean founder,
        boolean founderActive,
        long openGuests) {}
