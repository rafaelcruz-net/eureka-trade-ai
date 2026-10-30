package com.memetrade.identity.domain.login;

import java.time.Instant;
import java.util.Optional;

/** Persistence port of login challenges. */
public interface LoginChallengeRepository {

    void insert(LoginChallenge challenge);

    Optional<LoginChallenge> findByNonce(String nonce);

    /**
     * ISSUED → CONSUMED only if still ISSUED and not expired at {@code now} (conditional update). Returns false when
     * another verification already consumed it, so a nonce is consumed at most once (BR1.3).
     */
    boolean consume(String nonce, Instant now);

    /** ISSUED → EXPIRED; returns false when the challenge was no longer ISSUED. */
    boolean expire(String nonce);
}
