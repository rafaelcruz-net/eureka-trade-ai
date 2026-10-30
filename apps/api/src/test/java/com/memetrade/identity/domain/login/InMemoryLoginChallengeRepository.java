package com.memetrade.identity.domain.login;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test double with the same conditional-update semantics as the PostgreSQL repository. */
public final class InMemoryLoginChallengeRepository implements LoginChallengeRepository {

    private final Map<String, LoginChallenge> challenges = new ConcurrentHashMap<>();

    @Override
    public void insert(LoginChallenge challenge) {
        if (challenges.putIfAbsent(challenge.nonce(), challenge) != null) {
            throw new IllegalStateException("duplicate nonce");
        }
    }

    @Override
    public Optional<LoginChallenge> findByNonce(String nonce) {
        return Optional.ofNullable(challenges.get(nonce));
    }

    @Override
    public synchronized boolean consume(String nonce, Instant now) {
        LoginChallenge current = challenges.get(nonce);
        if (current == null || current.state() != ChallengeState.ISSUED || now.isAfter(current.expiresAt())) {
            return false;
        }
        challenges.put(nonce, with(current, ChallengeState.CONSUMED, now));
        return true;
    }

    @Override
    public synchronized boolean expire(String nonce) {
        LoginChallenge current = challenges.get(nonce);
        if (current == null || current.state() != ChallengeState.ISSUED) {
            return false;
        }
        challenges.put(nonce, with(current, ChallengeState.EXPIRED, null));
        return true;
    }

    public int size() {
        return challenges.size();
    }

    private static LoginChallenge with(LoginChallenge c, ChallengeState state, Instant consumedAt) {
        return new LoginChallenge(
                c.nonce(),
                c.walletAddress(),
                c.domain(),
                c.chain(),
                c.message(),
                c.issuedAt(),
                c.expiresAt(),
                state,
                consumedAt);
    }
}
